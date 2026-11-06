'use strict';

// Paid plans without a payment gateway: the admin sells voucher codes (Telegram, card-to-card…),
// the user redeems a code in the app and gets the plan's bigger limits for N days.
// Also: per-device public keys for end-to-end encrypted shares.

const crypto = require('crypto');

const VOUCHER_CHARS = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function newVoucherCode() {
  let s = '';
  for (let i = 0; i < 12; i++) s += VOUCHER_CHARS[crypto.randomInt(VOUCHER_CHARS.length)];
  return `${s.slice(0, 4)}-${s.slice(4, 8)}-${s.slice(8)}`;
}

module.exports = function createPlans(pool, settings) {
  /** What this user may do right now: their active plan, or the free defaults. */
  async function limitsOf(userId) {
    const r = await pool.query(
      `SELECT u.quota_mb, u.plan_until, p.id AS plan_id, p.name, p.quota_mb AS p_quota, p.max_file_mb, p.max_ttl_hours
       FROM users u LEFT JOIN plans p ON p.id = u.plan_id AND p.active AND u.plan_until > now()
       WHERE u.id = $1`,
      [userId]
    );
    const u = r.rows[0] || {};
    if (u.plan_id) {
      return {
        plan: u.name,
        planId: u.plan_id,
        until: u.plan_until,
        quotaMb: Math.max(u.p_quota, u.quota_mb || 0),
        maxFileMb: Math.max(u.max_file_mb, settings.maxFileMb()),
        maxTtlHours: u.max_ttl_hours
      };
    }
    return { plan: 'Free', planId: null, until: null, quotaMb: u.quota_mb ?? settings.quotaMb(), maxFileMb: settings.maxFileMb(), maxTtlHours: 168 };
  }

  /** Same plan still running: add the days on top. Otherwise the new plan starts now. Returns the end date. */
  async function givePlan(db, userId, planId, days) {
    const u = (await db.query('SELECT plan_id, plan_until FROM users WHERE id = $1', [userId])).rows[0] || {};
    const from = u.plan_id === planId && u.plan_until && new Date(u.plan_until) > new Date() ? new Date(u.plan_until) : new Date();
    const until = new Date(from.getTime() + days * 86400000);
    await db.query('UPDATE users SET plan_id = $1, plan_until = $2 WHERE id = $3', [planId, until, userId]);
    return until;
  }

  /** New account: free trial of a plan, if the admin switched it on. */
  async function applyTrial(userId) {
    const planId = parseInt(settings.get('trialPlanId'), 10);
    const days = settings.int('trialDays', 0);
    if (!planId || days <= 0) return;
    const ok = (await pool.query('SELECT 1 FROM plans WHERE id = $1 AND active', [planId])).rowCount;
    if (ok) await givePlan(pool, userId, planId, days);
  }

  /** "Your plan ends in 3 days" and "your plan ended" notices (once each per plan period). */
  async function remindPlans() {
    const soon = await pool.query(
      `UPDATE users u SET plan_reminded = u.plan_until FROM plans p
       WHERE p.id = u.plan_id AND u.plan_until > now() AND u.plan_until < now() + interval '3 days'
         AND (u.plan_reminded IS NULL OR u.plan_reminded <> u.plan_until)
       RETURNING u.id, p.name, u.plan_until`
    );
    for (const r of soon.rows) {
      await pool.query(`INSERT INTO notices (user_id, kind, name, body) VALUES ($1, 'plan', $2, $3)`, [r.id, r.name, new Date(r.plan_until).toISOString()]);
    }
    const ended = await pool.query(
      `UPDATE users u SET plan_end_noticed = u.plan_until FROM plans p
       WHERE p.id = u.plan_id AND u.plan_until <= now() AND u.plan_until > now() - interval '7 days'
         AND (u.plan_end_noticed IS NULL OR u.plan_end_noticed <> u.plan_until)
       RETURNING u.id, p.name`
    );
    for (const r of ended.rows) {
      await pool.query(`INSERT INTO notices (user_id, kind, name) VALUES ($1, 'plan_end', $2)`, [r.id, r.name]);
    }
    return soon.rowCount + ended.rowCount;
  }

  const planJson = (p) => ({ id: p.id, name: p.name, quotaMb: p.quota_mb, maxFileMb: p.max_file_mb, maxTtlHours: p.max_ttl_hours, price: p.price_text || '' });

  function register(app, { authenticateToken, rateLimit, serverError }) {
    const redeemLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 15, message: 'Too many tries. Please wait a few minutes.' });

    app.get('/api/plans', authenticateToken, async (req, res) => {
      try {
        const plans = (await pool.query('SELECT * FROM plans WHERE active ORDER BY sort, quota_mb')).rows.map(planJson);
        const l = await limitsOf(req.user.id);
        res.json({
          current: { name: l.plan, until: l.until, quotaMb: l.quotaMb, maxFileMb: l.maxFileMb, maxTtlHours: l.maxTtlHours },
          free: { quotaMb: settings.quotaMb(), maxFileMb: settings.maxFileMb(), maxTtlHours: 168 },
          plans,
          buyInfo: settings.buyInfo()
        });
      } catch (err) { serverError(res, 'Plans error', err); }
    });

    app.post('/api/plans/redeem', redeemLimiter, authenticateToken, async (req, res) => {
      // Codes are matched without dashes/spaces: "abcd efgh ijkl" = "ABCD-EFGH-IJKL"; custom codes like "NOROOZ" too.
      const code = String(req.body.code || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
      if (code.length < 4 || code.length > 20) return res.status(400).json({ error: 'That code doesn\'t look right.' });
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const v = (await client.query(
          `SELECT v.*, p.name FROM vouchers v JOIN plans p ON p.id = v.plan_id WHERE REPLACE(v.code, '-', '') = $1 FOR UPDATE OF v`, [code]
        )).rows[0];
        if (!v) { await client.query('ROLLBACK'); return res.status(404).json({ error: 'No plan uses that code.' }); }
        const mine = (await client.query('SELECT 1 FROM voucher_uses WHERE code = $1 AND user_id = $2', [v.code, req.user.id])).rowCount;
        if (mine) { await client.query('ROLLBACK'); return res.status(409).json({ error: 'You already used this code.' }); }
        if (v.uses >= v.max_uses) { await client.query('ROLLBACK'); return res.status(409).json({ error: 'That code was already used.' }); }
        const until = await givePlan(client, req.user.id, v.plan_id, v.days);
        await client.query('INSERT INTO voucher_uses (code, user_id) VALUES ($1, $2)', [v.code, req.user.id]);
        await client.query(
          `UPDATE vouchers SET uses = uses + 1, used_by = COALESCE(used_by, $1), used_at = COALESCE(used_at, now()) WHERE code = $2`,
          [req.user.id, v.code]
        );
        await client.query(`INSERT INTO events (kind, user_id, bytes) VALUES ('redeem', $1, $2)`, [req.user.id, v.value || 0]);
        await client.query('COMMIT');
        res.json({ ok: true, plan: v.name, until });
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        serverError(res, 'Redeem error', err);
      } finally {
        client.release();
      }
    });

    // Each phone registers its public key so others can send it end-to-end encrypted files.
    app.post('/api/keys', authenticateToken, async (req, res) => {
      try {
        const deviceId = String(req.headers['x-device-id'] || req.body.deviceId || '').slice(0, 64);
        const publicKey = String(req.body.publicKey || '');
        if (!deviceId || publicKey.length < 40 || publicKey.length > 400) return res.status(400).json({ error: 'Bad key.' });
        await pool.query(
          `INSERT INTO device_keys (user_id, device_id, public_key, name) VALUES ($1, $2, $3, $4)
           ON CONFLICT (user_id, device_id) DO UPDATE SET public_key = EXCLUDED.public_key, name = EXCLUDED.name, updated_at = now()`,
          [req.user.id, deviceId, publicKey, String(req.body.name || '').slice(0, 60)]
        );
        res.json({ ok: true });
      } catch (err) { serverError(res, 'Key error', err); }
    });
  }

  return { limitsOf, register, newVoucherCode, givePlan, applyTrial, remindPlans };
};
