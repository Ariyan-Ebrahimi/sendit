'use strict';

// Admin accounts, sign-in sessions, roles, the activity log and the IP ban list.
//
//   owner      everything (the "admin" account whose password is ADMIN_PASSWORD in .env)
//   moderator  users (view, block), shares, reports, banned files, alerts, storage
//   support    users (view, message), support chat, messages

const crypto = require('crypto');
const bcrypt = require('bcryptjs');

const ROLES = ['owner', 'moderator', 'support'];

// Which roles may open each admin section (/admin/<section>/...).
const ACCESS = {
  '': ROLES, me: ROLES, logout: ROLES, search: ROLES, live: ROLES, 'live.json': ROLES,
  'pulse.json': ROLES, 'search.json': ROLES, 'notifications.json': ROLES, notifications: ROLES,
  users: ROLES,
  shares: ['owner', 'moderator'], file: ['owner', 'moderator'], reports: ['owner', 'moderator'],
  banned: ['owner', 'moderator'], alerts: ['owner', 'moderator'], storage: ['owner', 'moderator'],
  support: ['owner', 'support'], messages: ['owner', 'support']
  // everything else (settings, plans, sales, admins, log, security, backups, app, crashes, cleanup, users.csv): owner only
};

// User actions only the owner may do, even inside an allowed section.
const OWNER_ONLY = [/^\/admin\/users\/\d+\/(delete|plan|reset-password)$/];
// Support staff only look at users and write to them.
const SUPPORT_DENY = [/^\/admin\/users\/\d+\/(update|delete-shares)$/, /^\/admin\/security\//];

const hashToken = (t) => crypto.createHash('sha256').update(t).digest('hex');

module.exports = function createSecurity(pool) {
  // ---------------- IP bans ----------------
  let banned = new Map(); // ip -> reason

  async function loadBans() {
    const r = await pool.query('SELECT ip, reason FROM ip_bans');
    banned = new Map(r.rows.map((x) => [x.ip, x.reason || '']));
  }

  const normIp = (ip) => String(ip || '').replace(/^::ffff:/, '').trim().slice(0, 64);

  /** Blocks banned IPs everywhere except the admin panel (so the owner can't lock themselves out). */
  function ipBanMiddleware(req, res, next) {
    if (banned.size && banned.has(normIp(req.ip)) && !req.path.startsWith('/admin')) {
      if (req.path.startsWith('/api/')) return res.status(403).json({ error: 'Access from your network is blocked.' });
      return res.status(403).type('text').send('Access from your network is blocked.');
    }
    next();
  }

  async function banIp(ip, reason, by) {
    ip = normIp(ip);
    if (!ip) return;
    await pool.query(`INSERT INTO ip_bans (ip, reason, banned_by) VALUES ($1, $2, $3)
      ON CONFLICT (ip) DO UPDATE SET reason = EXCLUDED.reason`, [ip, String(reason || '').slice(0, 200), by]);
    await loadBans();
  }

  async function unbanIp(ip) {
    await pool.query('DELETE FROM ip_bans WHERE ip = $1', [normIp(ip)]);
    await loadBans();
  }

  // ---------------- admins & sessions ----------------
  const OWNER_PASSWORD = process.env.ADMIN_PASSWORD || '';
  const owner = { id: 0, username: 'admin', role: 'owner' };

  /** Checks a username/password. Returns the admin or null. */
  async function checkLogin(username, password) {
    username = String(username || '').trim().toLowerCase();
    password = String(password || '');
    if (!username || !password) return null;
    if (username === 'admin') {
      const a = Buffer.from(password), b = Buffer.from(OWNER_PASSWORD);
      return OWNER_PASSWORD && a.length === b.length && crypto.timingSafeEqual(a, b) ? owner : null;
    }
    const r = (await pool.query('SELECT * FROM admins WHERE username = $1 AND NOT disabled', [username])).rows[0];
    if (!r || !(await bcrypt.compare(password, r.password_hash))) return null;
    return { id: r.id, username: r.username, role: r.role };
  }

  async function startSession(admin, remember, req) {
    const token = crypto.randomBytes(32).toString('base64url');
    const hours = remember ? 24 * 30 : 12;
    await pool.query(
      `INSERT INTO admin_sessions (token_hash, admin_id, expires_at, ip, agent) VALUES ($1, $2, now() + make_interval(hours => $3), $4, $5)`,
      [hashToken(token), admin.id, hours, normIp(req.ip), String(req.headers['user-agent'] || '').slice(0, 200)]
    );
    if (admin.id) await pool.query('UPDATE admins SET last_login = now() WHERE id = $1', [admin.id]);
    return { token, maxAge: hours * 3600 };
  }

  async function sessionAdmin(token) {
    if (!token) return null;
    const r = (await pool.query(
      `SELECT s.admin_id, a.username, a.role, a.disabled FROM admin_sessions s LEFT JOIN admins a ON a.id = s.admin_id
       WHERE s.token_hash = $1 AND s.expires_at > now()`, [hashToken(token)]
    )).rows[0];
    if (!r) return null;
    if (r.admin_id === 0) return owner;
    if (!r.username || r.disabled) return null;
    return { id: r.admin_id, username: r.username, role: r.role };
  }

  async function endSession(token) {
    if (token) await pool.query('DELETE FROM admin_sessions WHERE token_hash = $1', [hashToken(token)]);
  }

  function canOpen(role, path) {
    if (role === 'owner') return true;
    const section = (path.split('/')[2] || '').toLowerCase();
    if (!(ACCESS[section] || []).includes(role)) return false;
    if (role === 'support' && SUPPORT_DENY.some((re) => re.test(path))) return false;
    return !OWNER_ONLY.some((re) => re.test(path));
  }

  // ---------------- activity log ----------------
  /** Something admins should see in the 🔔 list. [roles]: who sees it. */
  async function notify(kind, title, body, link, roles = 'owner') {
    await pool.query('INSERT INTO admin_notices (kind, title, body, link, roles) VALUES ($1, $2, $3, $4, $5)',
      [kind, String(title).slice(0, 200), body ? String(body).slice(0, 500) : null, link || null, roles]).catch((e) => console.error('admin notice:', e.message));
  }

  async function log(admin, action, target, detail, ip) {
    await pool.query(
      'INSERT INTO admin_log (admin_name, role, action, target, detail, ip) VALUES ($1, $2, $3, $4, $5, $6)',
      [admin ? admin.username : '?', admin ? admin.role : '', String(action).slice(0, 120), target ? String(target).slice(0, 120) : null,
        detail ? String(detail).slice(0, 1000) : null, normIp(ip)]
    ).catch((e) => console.error('admin log:', e.message));
  }

  return {
    ROLES, owner, loadBans, ipBanMiddleware, banIp, unbanIp, normIp, bannedIps: () => banned,
    checkLogin, startSession, sessionAdmin, endSession, canOpen, log, hashToken, notify
  };
};
