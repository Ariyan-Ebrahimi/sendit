'use strict';

// "Anywhere" shares: a sender uploads one or more files, gets a 6-character code and a link
// (http://SERVER/s/CODE). The link speaks the same protocol as the phone-to-phone Wi-Fi mode:
//   GET /s/CODE                 -> download web page (for people without the app)
//   GET /s/CODE/manifest.json   -> { app, sender, code, files: [{ id, name, size, mime }] }
//   GET /s/CODE/f/ID            -> the file bytes (supports Range, so downloads can resume)
// so the Sendit app downloads from the server exactly like it downloads from a nearby phone.
//
// Extras: resumable uploads (X-Upload-Offset), optional password, expiry choice,
// delete-after-first-download, sending to a specific Sendit user (their inbox), "My shares".

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');
const { pipeline } = require('stream/promises');

const CODE_CHARS = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
const MAX_FILES_PER_SHARE = 100;
const ALLOWED_TTL_HOURS = [1, 24, 168, 720]; // 720 h (30 days) needs a plan
const DESTRUCT_SECONDS = [10, 30, 60, 300];
const GEO_RADII = [100, 500, 2000];
const ONE_TIME_GRACE_MINUTES = 10; // lets a resumed download finish after the first full copy

function generateCode() {
  let code = '';
  for (let i = 0; i < 6; i++) code += CODE_CHARS[crypto.randomInt(CODE_CHARS.length)];
  return code;
}

function cleanCode(raw) {
  return String(raw || '').trim().toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 16);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function formatBytes(n) {
  n = Number(n) || 0;
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

function contentDisposition(name) {
  const ascii = name.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}

/** File names may include sub-folders ("Trip/day1/a.jpg"); strip anything that could escape them. */
function cleanName(raw, fallback) {
  const parts = String(raw || '').split(/[\/\\]+/).map((p) => p.trim()).filter((p) => p && p !== '.' && p !== '..');
  const joined = parts.join('/').replace(/[\x00-\x1f]/g, '_').slice(-255);
  return joined || fallback;
}

/** Metres between two points on Earth. */
function distanceM(lat1, lng1, lat2, lng2) {
  const r = (d) => (d * Math.PI) / 180;
  const a = Math.sin(r(lat2 - lat1) / 2) ** 2 + Math.cos(r(lat1)) * Math.cos(r(lat2)) * Math.sin(r(lng2 - lng1) / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
const prettyDistance = (m) => (m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(m < 10000 ? 1 : 0)} km`);

function fileSize(full) {
  try { return fs.statSync(full).size; } catch { return 0; }
}

module.exports = function registerShares(app, deps) {
  const { pool, authenticateToken, optionalToken, rateLimit, serverError, UPLOAD_DIR, settings, plans, security } = deps;

  // Storage limit: what a user's live shares take on the server vs. their allowance.
  async function storageOf(userId) {
    const r = await pool.query(
      `SELECT COALESCE((SELECT SUM(f.size) FROM share_files f JOIN shares s ON s.id = f.share_id
                        WHERE (s.sender_user_id = u.id OR (s.request_id IS NOT NULL AND s.recipient_user_id = u.id))
                          AND s.expires_at > CURRENT_TIMESTAMP), 0)::bigint AS used
       FROM users u WHERE u.id = $1`,
      [userId]
    );
    const limits = await plans.limitsOf(userId);
    return { used: Number((r.rows[0] || {}).used || 0), quota: limits.quotaMb * 1048576, limits };
  }

  const createLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 60, message: 'You created too many shares. Please try again later.' });
  const viewLimiter = rateLimit({ windowMs: 60 * 1000, max: 240, message: 'Too many requests. Please slow down.' });
  const passwordLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 40, message: 'Too many password attempts. Please wait a few minutes.' });
  const lookupLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 60, message: 'Too many lookups. Please wait a few minutes.' });

  const storagePath = (name) => path.join(UPLOAD_DIR, path.basename(name));
  const publicBase = (req) => `${req.protocol}://${req.get('host')}`;

  async function loadShare(code) {
    const r = await pool.query('SELECT * FROM shares WHERE code = $1 AND expires_at > CURRENT_TIMESTAMP', [cleanCode(code)]);
    return r.rows[0] || null;
  }

  async function loadOwnedShare(req, res) {
    const share = await loadShare(req.params.code);
    const key = String(req.headers['x-owner-key'] || '');
    if (!share) { res.status(404).json({ error: 'This share has expired or does not exist.' }); return null; }
    const a = Buffer.from(key), b = Buffer.from(share.owner_key);
    if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
      res.status(403).json({ error: 'You are not the owner of this share.' });
      return null;
    }
    return share;
  }

  async function deleteShareFully(shareId) {
    const r = await pool.query('DELETE FROM share_files WHERE share_id = $1 RETURNING storage_path', [shareId]);
    await pool.query('DELETE FROM shares WHERE id = $1', [shareId]);
    for (const row of r.rows) if (row.storage_path) fs.unlink(storagePath(row.storage_path), () => {});
  }

  async function shareSummaries(whereSql, params, req) {
    const r = await pool.query(
      `SELECT s.code, s.sender_name, s.status, s.download_count, s.created_at, s.expires_at, s.one_time, s.message, s.release_at, s.group_name,
              (SELECT COUNT(*) FROM share_recipients sr WHERE sr.share_id = s.id)::int AS group_size, s.view_once,
              s.e2e, s.destruct_seconds, (s.geo_lat IS NOT NULL) AS geo, s.geo_radius,
              (SELECT COALESCE(json_agg(u.name ORDER BY d.at), '[]') FROM share_downloads d JOIN users u ON u.id = d.user_id WHERE d.share_id = s.id) AS downloaders,
              su.email AS sender_email,
              (s.password_hash IS NOT NULL) AS has_password, ru.name AS recipient_name, ru.email AS recipient_email,
              COUNT(f.id)::int AS file_count, COALESCE(SUM(f.size), 0)::bigint AS total_size,
              (ARRAY_AGG(f.name ORDER BY f.idx))[1] AS first_name
       FROM shares s
       LEFT JOIN share_files f ON f.share_id = s.id
       LEFT JOIN users ru ON ru.id = s.recipient_user_id
       LEFT JOIN users su ON su.id = s.sender_user_id
       WHERE ${whereSql} AND s.expires_at > CURRENT_TIMESTAMP
       GROUP BY s.id, ru.name, ru.email, su.email
       ORDER BY s.created_at DESC
       LIMIT 100`,
      params
    );
    return r.rows.map((s) => ({
      code: s.code,
      url: `${publicBase(req)}/s/${s.code}`,
      sender: s.sender_name,
      senderEmail: s.sender_email,
      message: s.message || '',
      status: s.status,
      downloads: s.download_count,
      createdAt: s.created_at,
      expiresAt: s.expires_at,
      oneTime: s.one_time,
      hasPassword: s.has_password,
      recipientName: s.group_name ? `${s.group_name} (${s.group_size})` : s.recipient_name,
      releaseAt: s.release_at,
      viewOnce: s.view_once,
      e2e: s.e2e,
      destructSeconds: s.destruct_seconds || 0,
      geoRadius: s.geo ? s.geo_radius : 0,
      downloaders: s.downloaders || [],
      recipientEmail: s.recipient_email,
      fileCount: s.file_count,
      totalSize: Number(s.total_size),
      firstName: s.first_name
    }));
  }

  // ---------- People ----------

  // Check that an email belongs to a Sendit user before sending to them.
  app.post('/api/users/lookup', lookupLimiter, authenticateToken, async (req, res) => {
    try {
      const email = String(req.body.email || '').trim().toLowerCase();
      const r = await pool.query('SELECT id, name, email FROM users WHERE email = $1', [email]);
      if (!r.rows[0]) return res.status(404).json({ error: 'No Sendit account uses that email.' });
      res.json({ name: r.rows[0].name, email: r.rows[0].email });
    } catch (err) {
      serverError(res, 'Lookup error', err);
    }
  });

  // ---------- Sender ----------

  const logEvent = (kind, userId, bytes) =>
    pool.query('INSERT INTO events (kind, user_id, bytes) VALUES ($1, $2, $3)', [kind, userId || null, bytes || 0]).catch(() => {});

  function cleanFiles(files) {
    return files.map((f, i) => ({
      idx: i,
      name: cleanName(f.name, `File ${i + 1}`),
      size: Math.max(0, Math.floor(Number(f.size) || 0)),
      mime: String(f.mime || 'application/octet-stream').slice(0, 100),
      storage: `${Date.now()}-${crypto.randomBytes(8).toString('hex')}-${i}`
    }));
  }

  /** Writes a share and its file rows; returns { share, ownerKey }. */
  async function insertShare({ senderUserId, senderName, clean, ttl, oneTime, passwordHash, recipientId, message, requestId, releaseAt, groupName, recipientIds, viewOnce, senderDevice, extra = {} }) {
    const ownerKey = crypto.randomBytes(24).toString('hex');
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      let share = null;
      for (let attempt = 0; attempt < 8 && !share; attempt++) {
        await client.query('SAVEPOINT pick_code');
        try {
          const r = await client.query(
            `INSERT INTO shares (code, owner_key, sender_user_id, sender_name, expires_at, password_hash, one_time, recipient_user_id, message, request_id, release_at, group_name, view_once, sender_device)
             VALUES ($1, $2, $3, $4, COALESCE($11::timestamptz, CURRENT_TIMESTAMP) + make_interval(hours => $5), $6, $7, $8, $9, $10, $11::timestamptz, $12, $13, $14)
             RETURNING id, code, expires_at, release_at`,
            [generateCode(), ownerKey, senderUserId, senderName, ttl, passwordHash, oneTime || !!viewOnce, recipientId, message || null, requestId || null,
              releaseAt || null, groupName || null, !!viewOnce, senderDevice || null]
          );
          share = r.rows[0];
          if (extra.e2e || extra.geo || extra.destructSeconds) {
            await client.query(
              `UPDATE shares SET e2e = $2, e2e_meta = $3, destruct_seconds = $4, geo_lat = $5, geo_lng = $6, geo_radius = $7 WHERE id = $1`,
              [share.id, !!extra.e2e, extra.e2eMeta || null, extra.destructSeconds || null,
                extra.geo ? extra.geo.lat : null, extra.geo ? extra.geo.lng : null, extra.geo ? extra.geo.radius : null]
            );
          }
        } catch (err) {
          if (err.code !== '23505') throw err;
          await client.query('ROLLBACK TO SAVEPOINT pick_code');
        }
      }
      if (!share) throw new Error('Could not allocate a unique code');
      for (const f of clean) {
        await client.query(
          'INSERT INTO share_files (share_id, idx, name, size, mime, storage_path) VALUES ($1, $2, $3, $4, $5, $6)',
          [share.id, f.idx, f.name, f.size, f.mime, f.storage]
        );
      }
      for (const uid of recipientIds || []) {
        await client.query('INSERT INTO share_recipients (share_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [share.id, uid]);
      }
      await client.query('COMMIT');
      return { share, ownerKey };
    } catch (err) {
      await client.query('ROLLBACK').catch(() => {});
      throw err;
    } finally {
      client.release();
    }
  }

  function checkFiles(res, files, maxFileMb = settings.maxFileMb(), encrypted = false) {
    if (files.length === 0) { res.status(400).json({ error: 'Choose at least one file.' }); return null; }
    if (files.length > MAX_FILES_PER_SHARE) { res.status(400).json({ error: `You can send up to ${MAX_FILES_PER_SHARE} files at once.` }); return null; }
    const clean = cleanFiles(files);
    const maxFileBytes = maxFileMb * 1024 * 1024 + (encrypted ? 64 * 1024 * 1024 : 0);
    const tooBig = clean.find((f) => f.size > maxFileBytes);
    if (tooBig) { res.status(413).json({ error: `"${tooBig.name}" is too large. The limit is ${maxFileMb} MB per file.` }); return null; }
    const blocked = encrypted ? [] : settings.blockedTypes();
    const bad = blocked.length ? clean.find((f) => blocked.includes((f.name.split('.').pop() || '').toLowerCase())) : null;
    if (bad) { res.status(400).json({ error: `Files like "${bad.name}" aren't allowed on this server.` }); return null; }
    return clean;
  }

  // Create a share: { senderName, files: [{ name, size, mime }], ttlHours?, oneTime?, password?, toEmail?, message? }
  app.post('/api/shares', createLimiter, authenticateToken, async (req, res) => {
    try {
      const e2e = req.body.e2e === true;
      const storage = await storageOf(req.user.id);
      const clean = checkFiles(res, Array.isArray(req.body.files) ? req.body.files : [], storage.limits.maxFileMb, e2e);
      if (!clean) return;
      const total = clean.reduce((sum, f) => sum + f.size, 0);
      if (storage.used + total > storage.quota) {
        return res.status(413).json({
          error: 'Not enough storage left. Delete some shares in My shares, or wait for them to expire.',
          usedBytes: storage.used,
          quotaBytes: storage.quota
        });
      }

      const ttl = ALLOWED_TTL_HOURS.includes(Number(req.body.ttlHours)) ? Number(req.body.ttlHours) : settings.ttlHours();
      if (ttl > storage.limits.maxTtlHours) {
        return res.status(403).json({ error: 'Links that last 30 days need a Sendit plan.', needsPlan: true });
      }
      const oneTime = req.body.oneTime === true;
      const password = typeof req.body.password === 'string' ? req.body.password : '';
      if (password && (password.length < 4 || password.length > 64)) {
        return res.status(400).json({ error: 'The password must be 4 to 64 characters.' });
      }
      const passwordHash = password ? await bcrypt.hash(password, 10) : null;
      const message = String(req.body.message || '').trim().slice(0, 1000);

      let recipientId = null, recipientName = null;
      const toEmail = String(req.body.toEmail || '').trim().toLowerCase();
      if (toEmail) {
        const r = await pool.query('SELECT id, name FROM users WHERE email = $1', [toEmail]);
        if (!r.rows[0]) return res.status(404).json({ error: 'No Sendit account uses that email.' });
        recipientId = r.rows[0].id;
        recipientName = r.rows[0].name;
      }

      // Send to my other devices (same account): it appears there, not on the phone that sent it.
      const senderDevice = String(req.headers['x-device-id'] || '').slice(0, 64) || null;
      if (req.body.toSelf === true) {
        recipientId = req.user.id;
        recipientName = 'My devices';
      }

      // Secret files: opened only inside the app, gone after one look (view once) or after a countdown (self-destruct).
      const destructSeconds = DESTRUCT_SECONDS.includes(Number(req.body.destructSeconds)) ? Number(req.body.destructSeconds) : 0;
      const viewOnce = req.body.viewOnce === true || destructSeconds > 0;
      if (viewOnce) {
        const bad = clean.find((f) => !/^(image|video)\//.test(f.mime) && f.mime !== 'text/plain' &&
          !/\.(jpe?g|png|gif|webp|heic|bmp|mp4|webm|3gp|mov|txt)$/i.test(f.name));
        if (bad) return res.status(400).json({ error: 'Secret files work with photos, videos and text only.' });
      }

      // Only opens near a place.
      let geo = null;
      if (req.body.geo && typeof req.body.geo === 'object') {
        const lat = Number(req.body.geo.lat), lng = Number(req.body.geo.lng), radius = Number(req.body.geo.radius);
        if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180) || !GEO_RADII.includes(radius)) {
          return res.status(400).json({ error: 'That place could not be used. Try again.' });
        }
        geo = { lat, lng, radius };
      }
      const e2eMeta = e2e ? String(req.body.e2eMeta || '').slice(0, 200000) : null;
      if (e2e && !e2eMeta) return res.status(400).json({ error: 'Encrypted share is missing its details.' });

      // Send to a whole group of my contacts.
      let groupName = null, recipientIds = [];
      if (req.body.groupId) {
        const g = (await pool.query('SELECT id, name FROM contact_groups WHERE id = $1 AND owner_id = $2', [parseInt(req.body.groupId, 10), req.user.id])).rows[0];
        if (!g) return res.status(404).json({ error: 'That group no longer exists.' });
        recipientIds = (await pool.query('SELECT user_id FROM contact_group_members WHERE group_id = $1', [g.id])).rows.map((r) => r.user_id);
        if (recipientIds.length === 0) return res.status(400).json({ error: 'That group has nobody in it.' });
        groupName = g.name;
        recipientName = `${g.name} (${recipientIds.length})`;
      }

      // Send later: files upload now, but nobody can see or open them before this time.
      let releaseAt = null;
      if (req.body.sendAt) {
        const t = new Date(Number(req.body.sendAt));
        if (isNaN(t) || t.getTime() < Date.now() - 60000 || t.getTime() > Date.now() + 30 * 86400000) {
          return res.status(400).json({ error: 'Pick a time within the next 30 days.' });
        }
        if (t.getTime() > Date.now() + 60000) releaseAt = t.toISOString();
      }

      const senderName = String(req.body.senderName || req.user.name || 'Sendit user').trim().slice(0, 100);
      const { share, ownerKey } = await insertShare({
        senderUserId: req.user.id, senderName, clean, ttl, oneTime, passwordHash, recipientId, message, releaseAt, groupName, recipientIds,
        viewOnce, senderDevice, extra: { e2e, e2eMeta, destructSeconds, geo }
      });
      // Encrypted: the sender locks the key for each phone of each receiver (their public keys).
      let recipientKeys = [];
      if (e2e) {
        const ids = recipientIds.length ? recipientIds : recipientId ? [recipientId] : [];
        if (ids.length) {
          recipientKeys = (await pool.query(
            `SELECT user_id AS "userId", device_id AS "deviceId", public_key AS "publicKey" FROM device_keys
             WHERE user_id = ANY($1) AND NOT (user_id = $2 AND device_id = $3)`,
            [ids, req.user.id, senderDevice || '']
          )).rows;
        }
      }
      res.status(201).json({
        recipientKeys,
        code: share.code,
        ownerKey,
        url: `${publicBase(req)}/s/${share.code}`,
        expiresAt: share.expires_at,
        releaseAt: share.release_at,
        recipientName,
        files: clean.map((f) => ({ id: f.idx, name: f.name, size: f.size }))
      });
    } catch (err) {
      serverError(res, 'Create share error', err);
    }
  });

  app.post('/api/shares/:code/keys', async (req, res) => {
    try {
      const share = await loadOwnedShare(req, res);
      if (!share) return;
      const keys = Array.isArray(req.body.keys) ? req.body.keys.slice(0, 500) : [];
      for (const k of keys) {
        await pool.query(
          `INSERT INTO share_keys (share_id, device_id, user_id, wrapped) VALUES ($1, $2, $3, $4)
           ON CONFLICT (share_id, device_id) DO UPDATE SET wrapped = EXCLUDED.wrapped`,
          [share.id, String(k.deviceId || '').slice(0, 64), parseInt(k.userId, 10) || null, String(k.wrapped || '').slice(0, 1000)]
        );
      }
      if (req.body.epk) await pool.query('UPDATE shares SET e2e_epk = $2 WHERE id = $1', [share.id, String(req.body.epk).slice(0, 400)]);
      res.json({ ok: true, stored: keys.length });
    } catch (err) {
      serverError(res, 'Share keys error', err);
    }
  });

  // ---------- File requests: a link others use to upload files TO me ----------

  const requestLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 60, message: 'Too many uploads. Please try again later.' });

  async function loadRequest(code) {
    const r = await pool.query(
      `SELECT fr.*, u.name AS owner_name FROM file_requests fr JOIN users u ON u.id = fr.owner_id
       WHERE fr.code = $1 AND fr.expires_at > CURRENT_TIMESTAMP AND NOT u.banned`,
      [cleanCode(code)]
    );
    return r.rows[0] || null;
  }

  app.post('/api/requests', createLimiter, authenticateToken, async (req, res) => {
    try {
      const title = String(req.body.title || '').trim().slice(0, 120);
      const ttl = ALLOWED_TTL_HOURS.includes(Number(req.body.ttlHours)) ? Number(req.body.ttlHours) : 168;
      let row = null;
      for (let i = 0; i < 8 && !row; i++) {
        try {
          row = (await pool.query(
            `INSERT INTO file_requests (code, owner_id, title, expires_at)
             VALUES ($1, $2, $3, CURRENT_TIMESTAMP + make_interval(hours => $4)) RETURNING code, title, expires_at`,
            [generateCode(), req.user.id, title, ttl]
          )).rows[0];
        } catch (err) { if (err.code !== '23505') throw err; }
      }
      res.status(201).json({ code: row.code, title: row.title, url: `${publicBase(req)}/r/${row.code}`, expiresAt: row.expires_at, uploads: 0 });
    } catch (err) { serverError(res, 'Create request error', err); }
  });

  app.get('/api/requests', authenticateToken, async (req, res) => {
    try {
      const r = await pool.query(
        `SELECT code, title, uploads, expires_at FROM file_requests WHERE owner_id = $1 AND expires_at > CURRENT_TIMESTAMP ORDER BY created_at DESC`,
        [req.user.id]
      );
      res.json({ requests: r.rows.map((x) => ({ code: x.code, title: x.title, uploads: x.uploads, expiresAt: x.expires_at, url: `${publicBase(req)}/r/${x.code}` })) });
    } catch (err) { serverError(res, 'List requests error', err); }
  });

  app.delete('/api/requests/:code', authenticateToken, async (req, res) => {
    try {
      await pool.query('DELETE FROM file_requests WHERE code = $1 AND owner_id = $2', [cleanCode(req.params.code), req.user.id]);
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Delete request error', err); }
  });

  // Someone (no account needed) starts uploading to a request: { name, message, files }
  app.post('/r/:code/start', requestLimiter, async (req, res) => {
    try {
      const fr = await loadRequest(req.params.code);
      if (!fr) return res.status(404).json({ error: 'This upload link has expired or does not exist.' });
      if (fr.uploads >= 50) return res.status(429).json({ error: 'This upload link has received the most uploads it can take.' });
      const clean = checkFiles(res, Array.isArray(req.body.files) ? req.body.files : []);
      if (!clean) return;
      const total = clean.reduce((sum, f) => sum + f.size, 0);
      const storage = await storageOf(fr.owner_id);
      if (storage.used + total > storage.quota) return res.status(413).json({ error: 'The person who asked for files has no storage left.' });
      const name = String(req.body.name || '').trim().slice(0, 100) || 'Someone';
      const message = String(req.body.message || '').trim().slice(0, 1000);
      const { share, ownerKey } = await insertShare({
        senderUserId: null, senderName: name, clean, ttl: 168, oneTime: false, passwordHash: null,
        recipientId: fr.owner_id, message: fr.title ? `[${fr.title}] ${message}`.trim() : message, requestId: fr.id
      });
      res.status(201).json({ code: share.code, ownerKey });
    } catch (err) { serverError(res, 'Request upload error', err); }
  });

  app.get(['/r/:code', '/r/:code/'], viewLimiter, async (req, res) => {
    try {
      const fr = await loadRequest(req.params.code);
      if (!fr) return res.status(404).type('html').send(page('Sendit', '<p class="msg">This upload link has expired or does not exist.</p>'));
      res.type('html').send(page('Send files to ' + escapeHtml(fr.owner_name), `
        <p class="from"><b>${escapeHtml(fr.owner_name)}</b> is asking you for files${fr.title ? `: <b>${escapeHtml(fr.title)}</b>` : '.'}</p>
        <div class="up">
          <input id="who" placeholder="Your name" maxlength="100">
          <textarea id="msg" placeholder="Message (optional)" maxlength="1000" rows="2"></textarea>
          <label class="pick"><input id="files" type="file" multiple><span>Choose files</span></label>
          <div id="list" class="note"></div>
          <button id="go" disabled>Send</button>
          <div id="bar" class="bar" hidden><i></i></div>
          <p id="status" class="note"></p>
        </div>
        <p class="note">Files go straight to ${escapeHtml(fr.owner_name)}'s Sendit inbox. No account needed.</p>
        <script>
        const $ = (id) => document.getElementById(id);
        const fmt = (n) => n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
        $('files').onchange = () => {
          const fs = [...$('files').files];
          $('list').textContent = fs.length ? fs.length + ' file(s) · ' + fmt(fs.reduce((a, f) => a + f.size, 0)) : '';
          $('go').disabled = !fs.length;
        };
        $('go').onclick = async () => {
          const fs = [...$('files').files];
          $('go').disabled = true; $('bar').hidden = false; $('status').textContent = 'Uploading…';
          try {
            const r = await fetch(location.pathname.replace(/\\/$/, '') + '/start', { method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ name: $('who').value, message: $('msg').value, files: fs.map((f) => ({ name: f.name, size: f.size, mime: f.type || 'application/octet-stream' })) }) });
            const s = await r.json();
            if (!r.ok) throw new Error(s.error || 'Upload failed');
            const total = fs.reduce((a, f) => a + f.size, 0); let done = 0;
            for (let i = 0; i < fs.length; i++) {
              await new Promise((ok, bad) => {
                const x = new XMLHttpRequest();
                x.open('PUT', '/api/shares/' + s.code + '/files/' + i);
                x.setRequestHeader('X-Owner-Key', s.ownerKey);
                x.upload.onprogress = (e) => { $('bar').firstChild.style.width = ((done + e.loaded) / total * 100) + '%'; };
                x.onload = () => x.status < 300 ? ok() : bad(new Error('Upload failed'));
                x.onerror = () => bad(new Error('Connection lost. Please try again.'));
                x.send(fs[i]);
              });
              done += fs[i].size;
            }
            const c = await fetch('/api/shares/' + s.code + '/complete', { method: 'POST', headers: { 'X-Owner-Key': s.ownerKey } });
            if (!c.ok) throw new Error('Upload failed');
            $('bar').firstChild.style.width = '100%';
            $('status').textContent = '✓ Sent! ${escapeHtml(fr.owner_name)} will get them in Sendit.';
          } catch (e) { $('status').textContent = e.message; $('go').disabled = false; }
        };
        </script>`));
    } catch (err) { serverError(res, 'Request page error', err); }
  });

  async function ownedFile(req, res) {
    const share = await loadOwnedShare(req, res);
    if (!share) return null;
    const r = await pool.query('SELECT * FROM share_files WHERE share_id = $1 AND idx = $2', [share.id, parseInt(req.params.id, 10)]);
    if (!r.rows[0]) { res.status(404).json({ error: 'Unknown file.' }); return null; }
    return { share, file: r.rows[0] };
  }

  // How many bytes of this file the server already has (to resume an interrupted upload).
  app.get('/api/shares/:code/files/:id/offset', async (req, res) => {
    try {
      const found = await ownedFile(req, res);
      if (!found) return;
      const { file } = found;
      res.json({ offset: fileSize(storagePath(file.storage_path)), size: Number(file.size), done: file.uploaded });
    } catch (err) {
      serverError(res, 'Offset error', err);
    }
  });

  // Upload (part of) one file as the raw request body. X-Upload-Offset says where this part starts;
  // it must equal what the server already has, so an interrupted upload continues where it stopped.
  app.put('/api/shares/:code/files/:id', async (req, res) => {
    try {
      const found = await ownedFile(req, res);
      if (!found) return req.resume();
      const { file } = found;
      const full = storagePath(file.storage_path);
      const expected = Number(file.size);
      const have = fileSize(full);
      const offset = parseInt(req.headers['x-upload-offset'] || '0', 10) || 0;

      if (offset !== have && offset !== 0) {
        req.resume();
        return res.status(409).json({ error: 'Upload position mismatch.', offset: have });
      }
      let received = 0;
      req.on('data', (chunk) => {
        received += chunk.length;
        if (offset + received > expected) req.destroy(new Error('TOO_LARGE'));
      });
      try {
        await pipeline(req, fs.createWriteStream(full, { flags: offset === 0 ? 'w' : 'a' }));
      } catch (err) {
        if (err && err.message === 'TOO_LARGE') {
          return res.status(413).json({ error: 'File is larger than announced.' });
        }
        return; // connection dropped: the bytes that arrived are kept, the client resumes later
      }
      const now = fileSize(full);
      const done = now === expected;
      if (done && !file.uploaded) await pool.query('UPDATE share_files SET uploaded = TRUE WHERE id = $1', [file.id]);
      res.json({ ok: true, offset: now, done });
    } catch (err) {
      if (!res.headersSent) serverError(res, 'Share upload error', err);
    }
  });

  app.post('/api/shares/:code/complete', async (req, res) => {
    try {
      const share = await loadOwnedShare(req, res);
      if (!share) return;
      const r = await pool.query('SELECT COUNT(*)::int AS missing FROM share_files WHERE share_id = $1 AND NOT uploaded', [share.id]);
      if (r.rows[0].missing > 0) return res.status(409).json({ error: `${r.rows[0].missing} file(s) are not uploaded yet.` });
      await pool.query(`UPDATE shares SET status = 'ready' WHERE id = $1`, [share.id]);
      fingerprint(share).catch((e) => console.error('fingerprint:', e.message));
      const size = Number((await pool.query('SELECT COALESCE(SUM(size), 0)::bigint AS n FROM share_files WHERE share_id = $1', [share.id])).rows[0].n);
      logEvent('share', share.sender_user_id, size);
      if (share.request_id) await pool.query('UPDATE file_requests SET uploads = uploads + 1 WHERE id = $1', [share.request_id]);
      res.json({ ok: true, code: share.code, url: `${publicBase(req)}/s/${share.code}`, expiresAt: share.expires_at });
    } catch (err) {
      serverError(res, 'Complete share error', err);
    }
  });

  app.get('/api/shares/:code/status', async (req, res) => {
    try {
      const share = await loadOwnedShare(req, res);
      if (!share) return;
      res.json({ code: share.code, status: share.status, downloads: share.download_count, expiresAt: share.expires_at });
    } catch (err) {
      serverError(res, 'Share status error', err);
    }
  });

  app.delete('/api/shares/:code', async (req, res) => {
    try {
      const share = await loadOwnedShare(req, res);
      if (!share) return;
      await deleteShareFully(share.id);
      res.json({ ok: true });
    } catch (err) {
      serverError(res, 'Delete share error', err);
    }
  });

  // ---------- Signed-in user: my shares + inbox ----------

  app.get('/api/my/shares', authenticateToken, async (req, res) => {
    try {
      const storage = await storageOf(req.user.id);
      res.json({
        shares: await shareSummaries(`s.sender_user_id = $1 AND s.status = 'ready'`, [req.user.id], req),
        usedBytes: storage.used,
        quotaBytes: storage.quota
      });
    } catch (err) {
      serverError(res, 'My shares error', err);
    }
  });

  app.delete('/api/my/shares/:code', authenticateToken, async (req, res) => {
    try {
      const r = await pool.query('SELECT id FROM shares WHERE code = $1 AND sender_user_id = $2', [cleanCode(req.params.code), req.user.id]);
      if (!r.rows[0]) return res.status(404).json({ error: 'Share not found.' });
      await deleteShareFully(r.rows[0].id);
      res.json({ ok: true });
    } catch (err) {
      serverError(res, 'Delete my share error', err);
    }
  });

  app.post('/api/my/shares/:code/extend', authenticateToken, async (req, res) => {
    try {
      const hours = ALLOWED_TTL_HOURS.includes(Number(req.body.hours)) ? Number(req.body.hours) : 24;
      const r = await pool.query(
        `UPDATE shares SET expires_at = GREATEST(expires_at, now()) + make_interval(hours => $3), reminded = FALSE
         WHERE code = $1 AND sender_user_id = $2 AND expires_at > now() RETURNING expires_at`,
        [cleanCode(req.params.code), req.user.id, hours]
      );
      if (!r.rows[0]) return res.status(404).json({ error: 'Share not found.' });
      res.json({ ok: true, expiresAt: r.rows[0].expires_at });
    } catch (err) {
      serverError(res, 'Extend share error', err);
    }
  });

  app.get('/api/inbox', authenticateToken, async (req, res) => {
    try {
      res.json({
        shares: await shareSummaries(
          `(s.recipient_user_id = $1 OR EXISTS (SELECT 1 FROM share_recipients sr WHERE sr.share_id = s.id AND sr.user_id = $1))
           AND s.status = 'ready' AND NOT s.blocked AND (s.release_at IS NULL OR s.release_at <= CURRENT_TIMESTAMP)
           AND NOT (s.sender_user_id = $1 AND COALESCE(s.sender_device, '') = $2 AND $2 <> '')`,
          [req.user.id, String(req.headers['x-device-id'] || '')], req)
      });
    } catch (err) {
      serverError(res, 'Inbox error', err);
    }
  });

  // ---------- Receiver (same protocol as the phone-to-phone Wi-Fi mode) ----------

  function suppliedPassword(req) {
    return String(req.headers['x-share-password'] || req.query.p || '');
  }

  // Returns the share if it is ready and (when protected) the password is right; otherwise answers.
  async function readyShareOr404(req, res, asHtml) {
    const share = await loadShare(req.params.code);
    if (share && share.blocked) {
      const msg = 'These files were removed by Sendit because they broke the rules.';
      if (asHtml) res.status(451).type('html').send(page('Sendit', `<p class="msg">${msg}</p>`));
      else res.status(451).json({ error: msg });
      return null;
    }
    if (!share || share.status !== 'ready') {
      const uploading = share && share.status !== 'ready';
      const msg = uploading
        ? 'The sender is still uploading. Try again in a moment.'
        : 'No files found for this code. It may have expired.';
      if (asHtml) res.status(uploading ? 409 : 404).type('html').send(page('Sendit', `<p class="msg">${escapeHtml(msg)}</p>`));
      else res.status(uploading ? 409 : 404).json({ error: msg });
      return null;
    }
    const appOnly = (msg) => {
      if (asHtml) res.status(403).type('html').send(page('Sendit', `<p class="msg">${msg}</p>`));
      else res.status(403).json({ error: msg });
      return null;
    };
    if (share.e2e && (asHtml || req.headers['x-sendit-app'] !== '1')) {
      return appOnly('These files are end-to-end encrypted 🔒 Only the Sendit app of the receiver can open them.');
    }
    if (share.geo_lat !== null && share.geo_lat !== undefined) {
      if (asHtml) return appOnly('📍 These files open only near a place the sender picked. Open the code in the Sendit app when you are there.');
      const m = /^(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)$/.exec(String(req.headers['x-sendit-location'] || '').replace(/\s/g, ''));
      const radius = share.geo_radius;
      if (!m) {
        res.status(403).json({ error: '📍 These files open only near a place the sender picked.', needsLocation: true, radius });
        return null;
      }
      const d = distanceM(Number(m[1]), Number(m[2]), share.geo_lat, share.geo_lng);
      if (d > radius) {
        res.status(403).json({
          error: `📍 You're ${prettyDistance(d)} away. Get within ${prettyDistance(radius)} of the place to open these files.`,
          needsLocation: true, radius, distance: Math.round(d)
        });
        return null;
      }
    }
    if (share.view_once && (asHtml || req.headers['x-sendit-app'] !== '1')) {
      const msg = 'This is a secret share. Open it in the Sendit app.';
      if (asHtml) res.status(403).type('html').send(page('Sendit', `<p class="msg">${msg}</p>`));
      else res.status(403).json({ error: msg });
      return null;
    }
    if (share.release_at && new Date(share.release_at) > new Date()) {
      const msg = 'These files are not available yet.';
      if (asHtml) res.status(425).type('html').send(page('Sendit', `<p class="msg">${msg}<br><small>Available from ${escapeHtml(new Date(share.release_at).toUTCString())}</small></p>`));
      else res.status(425).json({ error: msg, availableAt: share.release_at });
      return null;
    }
    if (share.password_hash) {
      const given = suppliedPassword(req);
      const ok = given ? await bcrypt.compare(given, share.password_hash) : false;
      if (!ok) {
        const msg = given ? 'Wrong password.' : 'This share is protected by a password.';
        if (asHtml) {
          res.status(401).type('html').send(page('Sendit', `
            <p class="from">${escapeHtml(msg)}</p>
            <form method="get" class="pw">
              <input type="password" name="p" placeholder="Password" autofocus required>
              <button type="submit">Open</button>
            </form>`));
        } else {
          res.status(401).json({ error: msg, needsPassword: true });
        }
        return null;
      }
    }
    return share;
  }

  async function shareFiles(shareId) {
    const r = await pool.query('SELECT id, idx, name, size, mime, storage_path FROM share_files WHERE share_id = $1 ORDER BY idx', [shareId]);
    return r.rows;
  }

  // A full copy of one file was delivered: count it and, for one-time shares, schedule deletion.
  async function onFileDelivered(share, file, user) {
    await pool.query('UPDATE share_files SET completed_downloads = completed_downloads + 1 WHERE id = $1', [file.id]);
    if (file.idx === 0 && user) {
      await pool.query('INSERT INTO share_downloads (share_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [share.id, user.id]).catch(() => {});
    }
    if (file.idx === 0) {
      await pool.query('UPDATE shares SET download_count = download_count + 1 WHERE id = $1', [share.id]);
      logEvent('download', null, 0);
      // Tell the sender (at most once every 10 minutes per share).
      if (share.sender_user_id) {
        const r = await pool.query(
          `UPDATE shares SET last_download_notice = now() WHERE id = $1
             AND (last_download_notice IS NULL OR last_download_notice < now() - interval '10 minutes') RETURNING id`,
          [share.id]
        );
        if (r.rowCount) {
          const n = (await pool.query('SELECT COUNT(*)::int AS n FROM share_files WHERE share_id = $1', [share.id])).rows[0].n;
          await pool.query(
            `INSERT INTO notices (user_id, kind, code, name, count, body) VALUES ($1, 'downloaded', $2, $3, $4, $5)`,
            [share.sender_user_id, share.code, file.name, n, user && user.id !== share.sender_user_id ? user.name : null]
          );
        }
      }
    }
    if (share.one_time) {
      const r = await pool.query('SELECT COUNT(*)::int AS left FROM share_files WHERE share_id = $1 AND completed_downloads = 0', [share.id]);
      if (r.rows[0].left === 0) {
        await pool.query(
          `UPDATE shares SET expires_at = LEAST(expires_at, CURRENT_TIMESTAMP + make_interval(mins => $2)) WHERE id = $1`,
          [share.id, share.view_once ? 1 : ONE_TIME_GRACE_MINUTES]
        );
      }
    }
  }

  app.get('/s/:code/manifest.json', viewLimiter, passwordLimiter, async (req, res) => {
    try {
      const share = await readyShareOr404(req, res, false);
      if (!share) return;
      const files = await shareFiles(share.id);
      let e2e = undefined;
      if (share.e2e) {
        const device = String(req.headers['x-device-id'] || '').slice(0, 64);
        const k = device ? (await pool.query('SELECT wrapped FROM share_keys WHERE share_id = $1 AND device_id = $2', [share.id, device])).rows[0] : null;
        e2e = { meta: share.e2e_meta, epk: share.e2e_epk || null, wrappedKey: k ? k.wrapped : null };
      }
      res.json({
        app: 'Sendit',
        version: 2,
        cloud: true,
        sender: share.sender_name,
        code: share.code,
        expiresAt: share.expires_at,
        oneTime: share.one_time,
        viewOnce: share.view_once,
        destructSeconds: share.destruct_seconds || 0,
        e2e,
        message: share.message || '',
        files: files.map((f) => ({ id: f.idx, name: f.name, size: Number(f.size), mime: f.mime }))
      });
    } catch (err) {
      serverError(res, 'Manifest error', err);
    }
  });

  app.get('/s/:code/f/:id', viewLimiter, optionalToken, async (req, res) => {
    try {
      const share = await readyShareOr404(req, res, false);
      if (!share) return;
      const idx = parseInt(req.params.id, 10);
      const r = await pool.query('SELECT * FROM share_files WHERE share_id = $1 AND idx = $2', [share.id, idx]);
      const file = r.rows[0];
      if (!file || !file.storage_path) return res.status(404).json({ error: 'File not found.' });

      const full = storagePath(file.storage_path);
      let size;
      try { size = fs.statSync(full).size; } catch { return res.status(404).json({ error: 'File data is missing on the server.' }); }

      res.setHeader('Content-Type', file.mime || 'application/octet-stream');
      res.setHeader('Content-Disposition', contentDisposition(path.posix.basename(file.name)));
      res.setHeader('Accept-Ranges', 'bytes');

      let start = 0, end = size - 1;
      const range = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
      if (range && size > 0) {
        if (range[1] === '') { start = Math.max(0, size - Number(range[2])); }
        else { start = Number(range[1]); if (range[2] !== '') end = Math.min(Number(range[2]), size - 1); }
        if (start > end || start >= size) {
          res.setHeader('Content-Range', `bytes */${size}`);
          return res.status(416).end();
        }
        res.status(206);
        res.setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
      }
      res.setHeader('Content-Length', size === 0 ? 0 : end - start + 1);
      if (req.method === 'HEAD') return res.end();
      if (size === 0) {
        res.end();
        return onFileDelivered(share, file).catch(() => {});
      }

      const stream = fs.createReadStream(full, { start, end });
      stream.on('error', (err) => { console.error('Share download error:', err.message); res.destroy(err); });
      // The last byte reached the client: a complete copy now exists on their side (possibly resumed).
      res.on('finish', () => {
        if (end === size - 1) onFileDelivered(share, file, req.user).catch((e) => console.error('delivered:', e.message));
      });
      stream.pipe(res);
    } catch (err) {
      serverError(res, 'Share download error', err);
    }
  });

  // All files of a share in one ZIP (folders kept). Mostly for the web page.
  app.get('/s/:code/zip', viewLimiter, passwordLimiter, async (req, res) => {
    try {
      const share = await readyShareOr404(req, res, true);
      if (!share) return;
      const files = (await shareFiles(share.id)).filter((f) => f.storage_path);
      const archiver = require('archiver');
      const zip = archiver('zip', { store: true });
      res.setHeader('Content-Type', 'application/zip');
      res.setHeader('Content-Disposition', contentDisposition(`Sendit-${share.code}.zip`));
      zip.on('error', (err) => { console.error('ZIP error:', err.message); res.destroy(err); });
      res.on('finish', () => {
        for (const f of files) onFileDelivered(share, { id: f.id, idx: f.idx, name: f.name }).catch(() => {});
      });
      zip.pipe(res);
      for (const f of files) zip.file(storagePath(f.storage_path), { name: f.name });
      await zip.finalize();
    } catch (err) {
      if (!res.headersSent) serverError(res, 'ZIP error', err);
    }
  });

  app.get(['/s/:code', '/s/:code/'], viewLimiter, passwordLimiter, async (req, res) => {
    try {
      const share = await readyShareOr404(req, res, true);
      if (!share) return;
      const files = await shareFiles(share.id);
      const total = files.reduce((s, f) => s + Number(f.size), 0);
      const pw = suppliedPassword(req);
      const q = pw ? `?p=${encodeURIComponent(pw)}` : '';
      const list = files.map((f) => `
        <a class="file" href="/s/${share.code}/f/${f.idx}${q}">
          <span class="name">${escapeHtml(f.name)}</span>
          <span class="size">${formatBytes(f.size)}</span>
          <span class="dl">Download</span>
        </a>`).join('');
      const expires = new Date(share.expires_at).toUTCString();
      res.type('html').send(page(`Sendit · ${escapeHtml(share.sender_name)}`, `
        <p class="from"><b>${escapeHtml(share.sender_name)}</b> sent you ${files.length} ${files.length === 1 ? 'file' : 'files'} · ${formatBytes(total)}</p>
        ${share.message ? `<blockquote class="message">${escapeHtml(share.message)}</blockquote>` : ''}
        <div class="code">Code <b>${share.code}</b></div>
        ${files.length > 1 ? `<a class="zip" href="/s/${share.code}/zip${q}">⬇ Download all (ZIP) · ${formatBytes(total)}</a>` : ''}
        <div class="files">${list}</div>
        <p class="note">${share.one_time ? 'This link stops working after the first download.<br>' : ''}Available until ${escapeHtml(expires)}.<br>Have the Sendit app? Tap Receive and type <b>${share.code}</b>.<br><a href="/s/${share.code}/report" style="color:#918FA1">Report these files</a></p>`));
    } catch (err) {
      serverError(res, 'Share page error', err);
    }
  });

  // ---------- Banned files: every finished upload gets a SHA-256 fingerprint ----------

  function sha256Of(full) {
    return new Promise((resolve, reject) => {
      const h = crypto.createHash('sha256');
      fs.createReadStream(full).on('error', reject).on('data', (d) => h.update(d)).on('end', () => resolve(h.digest('hex')));
    });
  }

  /** Fingerprints a share's files; if one was banned by the admin, the share is blocked right away. */
  async function fingerprint(share) {
    if (share.e2e) return; // encrypted bytes are different every time: nothing to compare
    const files = (await pool.query('SELECT id, name, storage_path FROM share_files WHERE share_id = $1 AND sha256 IS NULL', [share.id])).rows;
    for (const f of files) {
      if (!f.storage_path) continue;
      const hash = await sha256Of(storagePath(f.storage_path));
      await pool.query('UPDATE share_files SET sha256 = $1 WHERE id = $2', [hash, f.id]);
      const ban = (await pool.query('SELECT reason FROM banned_hashes WHERE sha256 = $1', [hash])).rows[0];
      if (ban) {
        await pool.query('UPDATE shares SET blocked = TRUE, blocked_reason = $2 WHERE id = $1', [share.id, `Banned file: ${f.name}`]);
        console.log(`Blocked share ${share.code}: banned file ${f.name}`);
        return;
      }
    }
  }

  // ---------- Reports ("this file is bad") ----------

  const REPORT_REASONS = ['spam', 'illegal', 'violence', 'adult', 'copyright', 'virus', 'other'];
  const reportLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 15, message: 'Too many reports. Please try again later.' });

  async function saveReport(code, reason, details, userId, ip) {
    const share = (await pool.query('SELECT id, code FROM shares WHERE code = $1', [cleanCode(code)])).rows[0];
    if (!share) return false;
    await pool.query(
      `INSERT INTO reports (share_id, code, reason, details, reporter_id, reporter_ip) VALUES ($1, $2, $3, $4, $5, $6)`,
      [share.id, share.code, REPORT_REASONS.includes(reason) ? reason : 'other', String(details || '').trim().slice(0, 1000) || null, userId || null,
        String(ip || '').replace(/^::ffff:/, '').slice(0, 64)]
    );
    if (security) security.notify('report', `New report: ${share.code}`, `${REPORT_REASONS.includes(reason) ? reason : 'other'}${details ? ' — ' + String(details).slice(0, 120) : ''}`,
      '/admin/reports', 'owner,moderator');
    return true;
  }

  app.post('/api/report', reportLimiter, optionalToken, async (req, res) => {
    try {
      const ok = await saveReport(req.body.code, String(req.body.reason || ''), req.body.details, req.user && req.user.id, req.ip);
      if (!ok) return res.status(404).json({ error: 'No files found for this code. It may have expired.' });
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Report error', err); }
  });

  const reportForm = (code, done) => page('Report · Sendit', done
    ? '<p class="msg">Thank you. The Sendit team will look at it.</p>'
    : `<p class="from">Report the files of code <b>${escapeHtml(code)}</b></p>
      <form method="post" class="pw" style="flex-direction:column;align-items:stretch">
        <select name="reason">${REPORT_REASONS.map((r) => `<option value="${r}">${{ spam: 'Spam', illegal: 'Illegal content', violence: 'Violence', adult: 'Adult content',
          copyright: 'Copyright', virus: 'Virus or harmful file', other: 'Something else' }[r]}</option>`).join('')}</select>
        <textarea name="details" rows="3" maxlength="1000" placeholder="What is wrong? (optional)"></textarea>
        <button type="submit">Send report</button>
      </form>`);

  app.get('/s/:code/report', viewLimiter, (req, res) => res.type('html').send(reportForm(cleanCode(req.params.code), false)));
  app.post('/s/:code/report', reportLimiter, async (req, res) => {
    try {
      await saveReport(req.params.code, String(req.body.reason || ''), req.body.details, null, req.ip);
      res.type('html').send(reportForm(cleanCode(req.params.code), true));
    } catch (err) { serverError(res, 'Report page error', err); }
  });

  // ---------- Cleanup ----------

  return async function cleanupShares() {
    // About an hour before a share expires, remind the sender (only for shares meant to last over 2 hours).
    const due = await pool.query(
      `UPDATE shares SET reminded = TRUE
       WHERE NOT reminded AND status = 'ready' AND sender_user_id IS NOT NULL
         AND expires_at BETWEEN now() AND now() + interval '70 minutes'
         AND expires_at - COALESCE(release_at, created_at) > interval '2 hours'
       RETURNING id, code, sender_user_id`
    );
    for (const s of due.rows) {
      const f = (await pool.query('SELECT name FROM share_files WHERE share_id = $1 ORDER BY idx LIMIT 1', [s.id])).rows[0];
      await pool.query(`INSERT INTO notices (user_id, kind, code, name) VALUES ($1, 'expiring', $2, $3)`, [s.sender_user_id, s.code, f ? f.name : '']);
    }
    const r = await pool.query(
      `DELETE FROM share_files WHERE share_id IN (SELECT id FROM shares WHERE expires_at <= CURRENT_TIMESTAMP)
       RETURNING storage_path`
    );
    for (const row of r.rows) if (row.storage_path) fs.unlink(storagePath(row.storage_path), () => {});
    const s = await pool.query('DELETE FROM shares WHERE expires_at <= CURRENT_TIMESTAMP');
    if (s.rowCount > 0) console.log(`Cleanup: removed ${s.rowCount} expired share(s).`);
    const known = await pool.query('SELECT storage_path FROM share_files WHERE storage_path IS NOT NULL');
    return known.rows.map((row) => row.storage_path);
  };
};

function page(title, body) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;font-family:system-ui,-apple-system,Segoe UI,Roboto,Tahoma,sans-serif;background:#0F131D;color:#DFE2F1;
background-image:radial-gradient(circle at 15% 0%,rgba(79,70,229,.35),transparent 55%),radial-gradient(circle at 100% 100%,rgba(76,215,246,.12),transparent 50%)}
main{max-width:560px;margin:0 auto;padding:40px 16px}
.brand{display:flex;align-items:center;gap:12px;margin-bottom:28px}
.logo{width:44px;height:44px;border-radius:14px;background:linear-gradient(135deg,#4338CA,#6D4BEA,#7C3AED);display:grid;place-items:center}
.brand h1{font-size:24px;margin:0}
.from{font-size:17px;margin:0 0 12px}
.code{display:inline-block;padding:6px 12px;border-radius:999px;background:#1C1F2A;color:#C7C4D8;font-size:13px;margin-bottom:18px}
.code b{color:#C3C0FF;letter-spacing:2px}
.files{display:flex;flex-direction:column;gap:10px}
.file{display:flex;align-items:center;gap:12px;padding:14px 16px;border-radius:18px;background:#171B26;border:1px solid rgba(255,255,255,.06);color:inherit;text-decoration:none}
.file:hover{border-color:rgba(76,215,246,.5)}
.name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600}
.size{color:#918FA1;font-size:13px;white-space:nowrap}
.dl,button{background:linear-gradient(90deg,#4F46E5,#7C3AED);color:#fff;font-size:13px;font-weight:600;padding:8px 14px;border-radius:999px;border:0}
.pw{display:flex;gap:10px;margin-top:8px}
.pw input{flex:1;min-width:0;padding:12px 16px;border-radius:999px;border:1px solid #464555;background:#171B26;color:#DFE2F1;font-size:16px}
.pw button{font-size:15px;padding:12px 20px}
.note,.msg{color:#918FA1;font-size:14px;line-height:1.5;margin-top:22px}
.zip{display:block;text-align:center;margin:0 0 14px;padding:14px;border-radius:16px;background:linear-gradient(90deg,#4F46E5,#7C3AED);color:#fff;font-weight:700;text-decoration:none}
.message{margin:0 0 18px;padding:12px 16px;border-radius:14px;background:#171B26;border-left:3px solid #4CD7F6;white-space:pre-wrap}
.up{display:flex;flex-direction:column;gap:10px}
.up input,.up textarea{padding:12px 16px;border-radius:14px;border:1px solid #464555;background:#171B26;color:#DFE2F1;font:inherit;font-size:16px}
.up .pick{display:block;text-align:center;padding:22px;border-radius:16px;border:2px dashed #464555;cursor:pointer;color:#4CD7F6;font-weight:600}
.up .pick input{display:none}.up button{padding:14px;font-size:16px}.up button:disabled{opacity:.4}
.bar{height:8px;border-radius:9px;background:#262A35;overflow:hidden}.bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,#4CD7F6,#4F46E5);transition:width .2s}
.msg{font-size:16px;color:#DFE2F1}
</style></head><body><main>
<div class="brand"><div class="logo"><svg width="34" height="34" viewBox="27 27 54 54"><path d="M43,75 H65 A9,9 0 0 0 65,57 H53 A9,9 0 0 1 53,39 H72 M66,33 L72,39 L66,45" stroke="#fff" stroke-width="7" fill="none" stroke-linecap="round" stroke-linejoin="round"/><circle cx="35" cy="75" r="4" fill="#4CD7F6"/></svg></div><h1>Sendit</h1></div>
${body}
</main></body></html>`;
}
