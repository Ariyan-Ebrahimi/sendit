'use strict';

// Sendit admin panel at /admin (user "admin", password = ADMIN_PASSWORD in .env).
//
//   Dashboard  numbers, disk space, password-reset codes, biggest users, newest shares
//   Users      search, open a user: edit name/email/limit, block, new password, delete
//   Shares     search, open a share: see/preview every file, extend, remove password, delete
//   Settings   sign-ups on/off, announcement shown in the app, limits, cleanup
//
// Every change is a POST with a per-install token, so other websites can't trigger actions.

const crypto = require('crypto');
const os = require('os');
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');
const { deleteUserCompletely } = require('./account');
const { AsyncLocalStorage } = require('async_hooks');
const computeAlerts = require('./alerts');
const ui = require('./admin-ui');

// The signed-in admin, readable anywhere while a request is handled (for the menu in layout()).
const current = new AsyncLocalStorage();
let securityRef = null;
let csrfRef = '';
const readCookie = (req) => {
  const m = /(?:^|;\s*)sendit_admin=([^;]+)/.exec(req.headers.cookie || '');
  return m ? decodeURIComponent(m[1]) : null;
};

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const when = (d) => (d ? new Date(d).toISOString().replace('T', ' ').slice(0, 16) + ' UTC' : '');
function bytes(n) {
  n = Number(n) || 0;
  if (n < 1024) return `${n} B`;
  const u = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${u[i]}`;
}
function left(d) {
  const ms = new Date(d) - Date.now();
  if (ms <= 0) return 'expired';
  const m = Math.round(ms / 60000);
  return m < 60 ? `${m} min left` : m < 2880 ? `${Math.round(m / 60)} h left` : `${Math.round(m / 1440)} days left`;
}
const kindOf = (name, mime) => {
  const n = String(name).toLowerCase();
  if (/^image\//.test(mime) || /\.(jpe?g|png|gif|webp|bmp|heic)$/.test(n)) return 'image';
  if (/^video\//.test(mime) || /\.(mp4|webm|mkv|mov|3gp)$/.test(n)) return 'video';
  if (/^audio\//.test(mime) || /\.(mp3|m4a|ogg|wav|aac|flac)$/.test(n)) return 'audio';
  if (mime === 'application/pdf' || n.endsWith('.pdf')) return 'pdf';
  if (/^text\//.test(mime) || /\.(txt|md|csv|json|log|xml|html?|js|css|kt|java|py|ini|yaml|yml)$/.test(n)) return 'text';
  return 'other';
};

module.exports = function registerAdmin(app, deps) {
  const { pool, serverError, UPLOAD_DIR, settings, cleanupNow, plans, traffic, security, backups, APP_DIR } = deps;
  securityRef = security;
  const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
  const csrf = crypto.createHmac('sha256', ADMIN_PASSWORD || 'none').update('sendit-admin-csrf').digest('hex').slice(0, 32);
  csrfRef = csrf;
  const failures = new Map();

  // ---------------- access ----------------

  const tooMany = (ip) => {
    const f = failures.get(ip);
    return f && f.until > Date.now() && f.count >= 10;
  };
  const failed = (ip) => {
    const now = Date.now();
    const f = failures.get(ip);
    const entry = f && f.until > now ? f : { count: 0, until: now + 15 * 60 * 1000 };
    entry.count += 1;
    failures.set(ip, entry);
  };
  // What an action changed, for the activity log (never passwords).
  const describe = (body) => {
    const o = {};
    for (const [k, v] of Object.entries(body || {})) {
      if (k === 'csrf' || /pass/i.test(k)) continue;
      o[k] = Array.isArray(v) ? v.slice(0, 50) : String(v).slice(0, 200);
    }
    return Object.keys(o).length ? JSON.stringify(o) : null;
  };

  async function requireAdmin(req, res, next) {
    try {
      if (!ADMIN_PASSWORD) {
        return res.status(503).type('html').send(layout('Admin', '', '<p class="msg">Set ADMIN_PASSWORD in server/.env and restart the server to use this page.</p>'));
      }
      let admin = await security.sessionAdmin(readCookie(req));
      // Scripts (and the tests) may still use HTTP Basic auth.
      const header = req.headers.authorization || '';
      if (!admin && header.startsWith('Basic ')) {
        if (tooMany(req.ip)) return res.status(429).send('Too many wrong passwords. Try again in 15 minutes.');
        const [user, pass] = Buffer.from(header.slice(6), 'base64').toString('utf8').split(/:(.*)/s);
        admin = await security.checkLogin(user, pass);
        if (!admin) failed(req.ip);
      }
      if (!admin) {
        if (req.method === 'GET' && /text\/html/.test(req.headers.accept || '')) {
          return res.redirect(303, '/admin/login?next=' + encodeURIComponent(req.originalUrl));
        }
        return res.status(401).send('Sign in at /admin/login.');
      }
      req.admin = admin;
      if (!security.canOpen(admin.role, req.path)) {
        return current.run(admin, () => res.status(403).type('html').send(layout('No access', '',
          `<p class="msg">Your role (<b>${esc(admin.role)}</b>) can't open this page. Ask the owner if you need it.</p>`)));
      }
      const writing = req.method !== 'GET' && req.method !== 'HEAD';
      if (writing && (req.body?.csrf || req.headers['x-csrf']) !== csrf) return res.status(403).send('Expired form. Reload the admin page and try again.');
      // Activity log: every change, plus looking at people's files and exports.
      if (writing || /^\/admin\/(file\/|users\.csv|backups\/.+\/download)/.test(req.path)) {
        res.on('finish', () => {
          if (res.statusCode < 400) security.log(admin, `${req.method} ${req.path}`, null, writing ? describe(req.body) : null, req.ip);
        });
      }
      res.setHeader('Cache-Control', 'no-store');
      current.run(admin, next);
    } catch (err) {
      serverError(res, 'Admin auth error', err);
    }
  }

  // ---------------- sign in / out ----------------

  const loginPage = (msg, next) => `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Sign in · Sendit admin</title>
<style>:root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 30% 20%,#232a4a,#0F131D 60%);
font-family:system-ui,-apple-system,Segoe UI,Roboto,Tahoma,sans-serif;color:#DFE2F1}
form{width:min(360px,92vw);background:#171B26;border:1px solid #262A35;border-radius:22px;padding:28px;display:flex;flex-direction:column;gap:12px;box-shadow:0 20px 60px #0008}
h1{margin:0 0 4px;font-size:22px}p{margin:0 0 8px;color:#918FA1;font-size:14px}input{padding:12px;border-radius:12px;border:1px solid #464555;background:#0F131D;color:#DFE2F1;font:inherit}
label.c{display:flex;gap:8px;align-items:center;color:#C7C4D8;font-size:14px}button{padding:12px;border:0;border-radius:12px;background:#4F46E5;color:#fff;font:inherit;font-size:16px;cursor:pointer}
.err{background:#93000A;color:#FFDAD6;padding:10px 12px;border-radius:10px;font-size:14px}.logo{width:44px;height:44px;border-radius:14px;background:linear-gradient(135deg,#4CD7F6,#4F46E5);display:grid;place-items:center;font-size:22px}</style></head>
<body><form method="post" action="/admin/login"><div class="logo">⇪</div><h1>Sendit admin</h1><p>Sign in to manage Sendit.</p>
${msg ? `<div class="err">${esc(msg)}</div>` : ''}
<input name="username" placeholder="Username" autocomplete="username" required autofocus>
<input name="password" type="password" placeholder="Password" autocomplete="current-password" required>
<label class="c"><input type="checkbox" name="remember" value="1"> Keep me signed in for 30 days</label>
<input type="hidden" name="next" value="${esc(next || '/admin')}"><button>Sign in</button></form></body></html>`;

  ui.register(app);

  app.get('/admin/login', (req, res) => res.type('html').send(loginPage('', String(req.query.next || '/admin'))));

  app.post('/admin/login', async (req, res) => {
    try {
      const next = String(req.body.next || '/admin');
      const safeNext = next.startsWith('/admin') && !next.startsWith('/admin/login') ? next : '/admin';
      if (tooMany(req.ip)) return res.status(429).type('html').send(loginPage('Too many wrong passwords. Try again in 15 minutes.', safeNext));
      const admin = await security.checkLogin(req.body.username, req.body.password);
      if (!admin) {
        failed(req.ip);
        security.log({ username: String(req.body.username || '?').slice(0, 40), role: '' }, 'login failed', null, null, req.ip);
        return res.status(401).type('html').send(loginPage('Wrong username or password.', safeNext));
      }
      failures.delete(req.ip);
      const { token, maxAge } = await security.startSession(admin, req.body.remember === '1', req);
      res.setHeader('Set-Cookie', `sendit_admin=${token}; HttpOnly; SameSite=Strict; Path=/admin; Max-Age=${maxAge}`);
      security.log(admin, 'signed in', null, null, req.ip);
      res.redirect(303, safeNext);
    } catch (err) { serverError(res, 'Admin login error', err); }
  });

  app.post('/admin/logout', requireAdmin, async (req, res) => {
    try {
      await security.endSession(readCookie(req));
      res.setHeader('Set-Cookie', 'sendit_admin=; HttpOnly; SameSite=Strict; Path=/admin; Max-Age=0');
      res.redirect(303, '/admin/login');
    } catch (err) { serverError(res, 'Admin logout error', err); }
  });

  const hidden = `<input type="hidden" name="csrf" value="${csrf}">`;
  // Dangerous buttons get a few seconds to Undo; very dangerous ones (type) need the name typed in.
  const plain = (h) => String(h).replace(/<[^>]*>/g, '').trim();
  const button = (action, label, { cls = '', confirm = '', fields = '', type = '', undo = '' } = {}) => {
    const danger = /danger/.test(cls);
    const attrs = type ? ` data-type="${esc(type)}" data-confirm="${esc(confirm || plain(label) + '?')}"`
      : danger ? ` data-undo="${esc(undo || plain(label) + ' — starting in a few seconds')}"`
      : confirm ? ` data-confirm="${esc(confirm)}"` : '';
    return `<form method="post" action="${action}" class="inline"${attrs}>${hidden}${fields}<button class="${cls}">${label}</button></form>`;
  };
  const goBack = (req, res, msg, fallback = '/admin') => {
    let to = String(req.body?.back || fallback);
    if (!to.startsWith('/admin')) to = '/admin';
    res.redirect(303, to + (to.includes('?') ? '&' : '?') + 'msg=' + encodeURIComponent(msg));
  };
  const backField = (to) => `<input type="hidden" name="back" value="${esc(to)}">`;
  const flash = (req) => (req.query.msg ? `<div class="flash">${esc(req.query.msg)}</div>` : '');

  async function findShare(code) {
    const r = await pool.query('SELECT * FROM shares WHERE code = $1', [String(code || '').toUpperCase().replace(/[^0-9A-Z]/g, '')]);
    return r.rows[0] || null;
  }

  async function deleteShare(id) {
    const files = await pool.query('DELETE FROM share_files WHERE share_id = $1 RETURNING storage_path', [id]);
    await pool.query('DELETE FROM shares WHERE id = $1', [id]);
    for (const f of files.rows) if (f.storage_path) fs.unlink(path.join(UPLOAD_DIR, path.basename(f.storage_path)), () => {});
  }

  const shareRow = (s) => `<tr>
      <td class="code"><a href="/admin/shares/${s.code}">${s.code}</a>${s.locked ? ' 🔑' : ''}${s.e2e ? ' 🔐' : ''}${s.geo ? ' 📍' : ''}${s.view_once ? ' 👁' : ''}${s.one_time ? ' ①' : ''}</td>
      <td>${esc(s.sender_name)}<br><small>${esc(s.sender_email || '')}${s.to_email ? ' → ' + esc(s.to_email) : ''}</small></td>
      <td><a href="/admin/shares/${s.code}">${esc(s.first_name || '')}</a>${s.files > 1 ? ` <small>+${s.files - 1}</small>` : ''}<br><small>${bytes(s.size)}</small></td>
      <td>${s.download_count}</td>
      <td><small>${s.status === 'ready' ? left(s.expires_at) : esc(s.status)}</small></td>
    </tr>`;
  const SHARE_SELECT = `
    SELECT s.id, s.code, s.sender_name, s.status, u.email AS sender_email, s.download_count, s.created_at, s.expires_at, s.one_time,
           (s.password_hash IS NOT NULL) AS locked, ru.email AS to_email, s.e2e, (s.geo_lat IS NOT NULL) AS geo, s.view_once,
           COUNT(f.id)::int AS files, COALESCE(SUM(f.size), 0)::bigint AS size,
           (ARRAY_AGG(f.name ORDER BY f.idx))[1] AS first_name
    FROM shares s LEFT JOIN share_files f ON f.share_id = s.id
    LEFT JOIN users u ON u.id = s.sender_user_id LEFT JOIN users ru ON ru.id = s.recipient_user_id`;
  const shareTable = (rows) => `<table><tr><th>Code</th><th>From → to</th><th>Files</th><th>Downloads</th><th>Time left</th></tr>
      ${rows.map(shareRow).join('') || '<tr><td colspan="5"><small>Nothing here.</small></td></tr>'}</table>`;

  // ---------------- dashboard ----------------

  app.get('/admin', requireAdmin, async (req, res) => {
    try {
      let disk = null;
      try { const s = fs.statfsSync(UPLOAD_DIR); disk = { total: s.blocks * s.bsize, free: s.bavail * s.bsize }; } catch { /* old Node */ }
      const days = [7, 30, 90].includes(parseInt(req.query.days, 10)) ? parseInt(req.query.days, 10) : 30;
      const t = (await pool.query(`
        SELECT (SELECT COUNT(*) FROM users)::int AS users,
               (SELECT COUNT(*) FROM users WHERE created_at > now() - interval '7 days')::int AS new_users,
               (SELECT COUNT(*) FROM users WHERE banned)::int AS blocked,
               (SELECT COUNT(*) FROM shares WHERE expires_at > now() AND status = 'ready')::int AS live,
               (SELECT COUNT(*) FROM shares WHERE created_at > now() - interval '7 days')::int AS new_shares,
               (SELECT COALESCE(SUM(f.size), 0) FROM share_files f JOIN shares s ON s.id = f.share_id WHERE s.expires_at > now())::bigint AS stored,
               (SELECT COALESCE(SUM(download_count), 0) FROM shares)::int AS downloads`)).rows[0];
      const resets = (await pool.query(`
        SELECT u.id, u.name, u.email, pr.code, pr.emailed, pr.expires_at FROM password_resets pr JOIN users u ON u.id = pr.user_id
        WHERE NOT pr.used AND pr.expires_at > now() ORDER BY pr.created_at DESC`)).rows;
      const top = (await pool.query(`
        SELECT u.id, u.name, u.email, COALESCE(SUM(f.size), 0)::bigint AS used
        FROM users u JOIN shares s ON s.sender_user_id = u.id AND s.expires_at > now() JOIN share_files f ON f.share_id = s.id
        GROUP BY u.id ORDER BY used DESC LIMIT 5`)).rows;
      const latest = (await pool.query(`${SHARE_SELECT} WHERE s.expires_at > now() GROUP BY s.id, u.email, ru.email ORDER BY s.created_at DESC LIMIT 10`)).rows;
      const usedPct = disk ? Math.round(((disk.total - disk.free) / disk.total) * 100) : 0;
      const ann = settings.announcement();
      const daily = (await pool.query(`
        SELECT d::date AS day,
               (SELECT COUNT(*) FROM users WHERE created_at::date = d::date)::int AS signups,
               (SELECT COUNT(*) FROM events WHERE kind = 'share' AND at::date = d::date)::int AS shares,
               (SELECT COUNT(*) FROM events WHERE kind = 'download' AND at::date = d::date)::int AS downloads,
               (SELECT COALESCE(SUM(bytes), 0) FROM events WHERE kind = 'share' AND at::date = d::date)::bigint AS bytes
        FROM generate_series(current_date - ($1::int * 2 - 1), current_date, interval '1 day') d ORDER BY d`, [days])).rows;
      const extra = (await pool.query(`
        SELECT d::date AS day,
               (SELECT COUNT(DISTINCT user_id) FROM events WHERE kind = 'active' AND at::date = d::date)::int AS active,
               (SELECT COUNT(*) FROM events WHERE kind = 'redeem' AND at::date = d::date)::int AS redeems
        FROM generate_series(current_date - ($1::int * 2 - 1), current_date, interval '1 day') d ORDER BY d`, [days])).rows;
      // Each chart: this period + the one before it (dashed line, and the ↑/↓ %).
      const xchart = (id, title, rows, value, f = 'int') => {
        const pts = rows.map((d) => [new Date(d.day).toISOString().slice(0, 10), Number(value(d))]);
        return `<section class="widget" data-id="${id}"><div class="xchart" data-title="${esc(title)}" data-fmt="${f}"
          data-points='${JSON.stringify(pts.slice(days))}' data-prev='${JSON.stringify(pts.slice(0, days))}'></div></section>`;
      };
      const charts = [
        xchart('c-active', 'Active users', extra, (d) => d.active),
        xchart('c-signups', 'New users', daily, (d) => d.signups),
        xchart('c-shares', 'Shares sent', daily, (d) => d.shares),
        xchart('c-downloads', 'Downloads', daily, (d) => d.downloads),
        xchart('c-bytes', 'Data uploaded', daily, (d) => d.bytes, 'bytes'),
        xchart('c-codes', 'Plan codes used', extra, (d) => d.redeems)
      ].join('');
      const versions = (await pool.query(`SELECT COALESCE(app_version, 'unknown') AS v, COUNT(*)::int AS n FROM users
        WHERE last_seen > now() - interval '30 days' GROUP BY 1 ORDER BY n DESC LIMIT 10`)).rows;
      const alertCount = (await computeAlerts(pool, settings)).length;
      const openReports = (await pool.query(`SELECT COUNT(*)::int AS n FROM reports WHERE status = 'open'`)).rows[0].n;

      const unreadSupport = (await pool.query('SELECT COUNT(*)::int AS n FROM support_messages WHERE NOT from_admin AND NOT seen_by_admin')).rows[0].n;
      const prefs = await getPref(req.admin, 'dashboard');
      const notes = [
        alertCount ? `<div class="note">${ui.icon('alert')} <a href="/admin/alerts">${alertCount} thing(s) look suspicious</a></div>` : '',
        openReports ? `<div class="note">${ui.icon('flag')} <a href="/admin/reports">${openReports} open report(s) from users</a></div>` : '',
        unreadSupport ? `<div class="note">${ui.icon('chat')} <a href="/admin/support">${unreadSupport} unread support message(s)</a></div>` : '',
        ann ? `<div class="note">${ui.icon('megaphone')} Announcement in the app: <b>${esc(ann)}</b> · <a href="/admin/settings">change</a></div>` : ''
      ].join('');
      res.type('html').send(layout('Dashboard', 'dash', `
        ${flash(req)}
        <div class="pagehead"><h1 class="title">Dashboard</h1><span class="grow"></span>
          <span class="actions-row" style="margin:0">${[7, 30, 90].map((d) => `<a class="btn ${d === days ? 'primary' : ''}" href="/admin?days=${d}">${d} days</a>`).join('')}</span>
          <button type="button" data-act="customize">${ui.icon('layout', 'sm')} <span>Customize</span></button></div>
        <div class="editbar">${ui.icon('layout')} <b style="flex:1">Drag cards to move them · ⇔ width · ${ui.icon('eye', 'sm')} show/hide</b>
          <button type="button" data-act="w-reset">Reset</button><button type="button" data-act="w-cancel">Cancel</button><button type="button" class="primary" data-act="w-save">Save layout</button></div>
        <div class="widgets" id="widgets" data-prefs='${esc(JSON.stringify(prefs || null))}'>
          <section class="widget" data-id="kpis" data-w="2"><div class="cards">
            <div class="card"><div class="k">Disk free</div><div class="v">${disk ? bytes(disk.free) : '—'}</div>
              <div class="s">${disk ? `${usedPct}% of ${bytes(disk.total)} used` : ''}</div>${disk ? `<div class="bar ${usedPct > 85 ? 'warn' : ''}"><i style="width:${usedPct}%"></i></div>` : ''}</div>
            <div class="card"><div class="k">Files online</div><div class="v">${bytes(t.stored)}</div><div class="s">${t.live} live shares · ${t.new_shares} this week</div></div>
            <div class="card"><div class="k">Users</div><div class="v">${t.users}</div><div class="s">${t.new_users} new this week${t.blocked ? ` · ${t.blocked} blocked` : ''}</div></div>
            <div class="card"><div class="k">Downloads</div><div class="v">${t.downloads}</div><div class="s">sign-ups ${settings.signupsOpen() ? 'open' : '<b class="red">closed</b>'}</div></div>
          </div></section>
          ${notes ? `<section class="widget" data-id="notes" data-w="2">${notes}</section>` : ''}
          ${charts}
          <section class="widget" data-id="versions"><h2>App versions <small>active in 30 days</small></h2>
            <div class="panel" style="margin:0">${versions.map((v) => `<span class="tag">${v.v === 'unknown' ? 'older app' : 'v' + esc(v.v)} · ${v.n}</span>`).join(' ') || '<small>No data yet (sent by app 1.9+).</small>'}
            ${settings.int('minVersion') ? `<p class="hint">Minimum allowed: ${settings.int('minVersion')} · <a href="/admin/app">change</a></p>` : ''}</div></section>
          <section class="widget" data-id="topusers"><h2>Using the most space</h2><table><tr><th>User</th><th>Online</th></tr>
            ${top.map((u) => `<tr><td><a href="/admin/users/${u.id}">${esc(u.name)}</a><br><small>${esc(u.email)}</small></td><td>${bytes(u.used)}</td></tr>`).join('') || '<tr><td colspan="2"><small>Nobody yet.</small></td></tr>'}</table></section>
          ${resets.length ? `<section class="widget" data-id="resets" data-w="2"><h2>Password reset codes</h2>
            <p class="hint">${process.env.SMTP_HOST ? 'These were also emailed.' : 'Email is off, so give these codes to the users yourself (15 minutes each).'}</p>
            <table><tr><th>User</th><th>Code</th><th>Emailed</th><th>Expires</th></tr>
            ${resets.map((r) => `<tr><td><a href="/admin/users/${r.id}">${esc(r.name)}</a><br><small>${esc(r.email)}</small></td><td class="code big">${esc(r.code)}</td><td>${r.emailed ? 'yes' : 'no'}</td><td><small>${when(r.expires_at)}</small></td></tr>`).join('')}</table></section>` : ''}
          <section class="widget" data-id="newshares" data-w="2"><h2>Newest shares <small><a href="/admin/shares">all</a></small></h2>${shareTable(latest)}</section>
        </div>`));
    } catch (err) { serverError(res, 'Admin dashboard error', err); }
  });

  app.get('/admin/search', requireAdmin, async (req, res) => {
    const q = String(req.query.q || '').trim();
    if (/^[0-9A-Za-z]{6}$/.test(q)) {
      const s = await findShare(q).catch(() => null);
      if (s) return res.redirect(303, `/admin/shares/${s.code}`);
    }
    res.redirect(303, `/admin/users?q=${encodeURIComponent(q)}&also=shares`);
  });

  // ---------------- users ----------------

  app.get('/admin/users', requireAdmin, async (req, res) => {
    try {
      const q = String(req.query.q || '').trim();
      const filter = String(req.query.filter || '');
      const like = `%${q.toLowerCase()}%`;
      const users = (await pool.query(`
        SELECT u.id, u.name, u.email, u.created_at, u.quota_mb, u.banned,
               COUNT(s.id) FILTER (WHERE s.expires_at > now())::int AS live,
               COALESCE((SELECT SUM(f.size) FROM share_files f JOIN shares s2 ON s2.id = f.share_id
                         WHERE s2.sender_user_id = u.id AND s2.expires_at > now()), 0)::bigint AS used
        FROM users u LEFT JOIN shares s ON s.sender_user_id = u.id
        WHERE ($1 = '' OR LOWER(u.email) LIKE $2 OR LOWER(u.name) LIKE $2) AND ($3 <> 'blocked' OR u.banned)
        GROUP BY u.id ORDER BY u.created_at DESC LIMIT 300`, [q, like, filter])).rows;
      let sharesHtml = '';
      if (req.query.also === 'shares' && q) {
        const rows = (await pool.query(`${SHARE_SELECT}
          WHERE s.code = UPPER($1) OR EXISTS (SELECT 1 FROM share_files f2 WHERE f2.share_id = s.id AND LOWER(f2.name) LIKE $2)
          GROUP BY s.id, u.email, ru.email ORDER BY s.created_at DESC LIMIT 50`, [q, like])).rows;
        sharesHtml = `<h2>Shares matching "${esc(q)}"</h2>${shareTable(rows)}`;
      }
      res.type('html').send(layout('Users', 'users', `
        ${flash(req)}
        <form class="search" action="/admin/users"><input name="q" value="${esc(q)}" placeholder="Name or email"><select name="filter">
          <option value="">Everyone</option><option value="blocked" ${filter === 'blocked' ? 'selected' : ''}>Blocked only</option></select><button>Search</button>
          <a class="btn" href="/admin/users.csv">⬇ Export CSV</a></form>
        <form method="post" action="/admin/users/bulk" id="bulk" data-bulk>${hidden}
        <div class="panel search" style="align-items:center">
          <b>With ticked users:</b>
          <select name="action" onchange="document.querySelectorAll('.bulkx').forEach(e=>e.style.display=e.dataset.for.split(' ').includes(this.value)?'':'none')">
            <option value="message">Send a message</option>${req.admin.role !== 'support' ? '<option value="block">Block</option><option value="unblock">Unblock</option>' : ''}
            ${req.admin.role === 'owner' ? '<option value="plan">Give a plan</option><option value="delete">Delete accounts</option>' : ''}</select>
          <input class="bulkx" data-for="message" name="message" placeholder="Message (shows in their app)" maxlength="2000" style="flex:2">
          ${req.admin.role === 'owner' ? `<select class="bulkx" data-for="plan" name="plan" style="display:none">${(await pool.query('SELECT id, name FROM plans ORDER BY sort')).rows.map((p) => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select>
          <input class="bulkx" data-for="plan" name="days" type="number" value="30" min="1" max="3650" style="display:none;max-width:90px">` : ''}
          <button class="primary">Apply</button>
          <label class="hint"><input type="checkbox" onclick="document.querySelectorAll('input[name=ids]').forEach(c=>c.checked=this.checked)"> tick all</label>
        </div>
        <table><tr><th></th><th>User</th><th>Files online</th><th>Limit</th><th>Joined</th></tr>
        ${users.map((u) => {
          const quota = (u.quota_mb ?? settings.quotaMb()) * 1048576;
          const pct = Math.min(100, Math.round((Number(u.used) / quota) * 100));
          return `<tr><td><input type="checkbox" name="ids" value="${u.id}"></td><td><a href="/admin/users/${u.id}">${esc(u.name)}</a>${u.banned ? ' <span class="tag red">blocked</span>' : ''}<br><small>${esc(u.email)}</small></td>
            <td>${bytes(u.used)} <small>(${u.live})</small><div class="bar small ${pct > 90 ? 'warn' : ''}"><i style="width:${pct}%"></i></div></td>
            <td><small>${u.quota_mb ? `${u.quota_mb} MB` : `default (${settings.quotaMb()} MB)`}</small></td>
            <td><small>${when(u.created_at).slice(0, 10)}</small></td></tr>`;
        }).join('') || '<tr><td colspan="5"><small>No users found.</small></td></tr>'}
        </table></form>${sharesHtml}`));
    } catch (err) { serverError(res, 'Admin users error', err); }
  });

  // All users as a spreadsheet file.
  app.get('/admin/users.csv', requireAdmin, async (req, res) => {
    try {
      const rows = (await pool.query(`
        SELECT u.id, u.name, u.email, u.created_at, u.banned, COALESCE(u.quota_mb, $1) AS quota_mb,
               COUNT(DISTINCT s.id) FILTER (WHERE s.expires_at > now())::int AS live_shares,
               COALESCE((SELECT SUM(f.size) FROM share_files f JOIN shares s2 ON s2.id = f.share_id
                         WHERE s2.sender_user_id = u.id AND s2.expires_at > now()), 0)::bigint AS used_bytes,
               (SELECT COUNT(*) FROM shares s3 WHERE s3.sender_user_id = u.id)::int AS total_shares
        FROM users u LEFT JOIN shares s ON s.sender_user_id = u.id GROUP BY u.id ORDER BY u.id`, [settings.quotaMb()])).rows;
      const cell = (v) => {
        const t = String(v ?? '');
        return /[",\n]/.test(t) ? '"' + t.replace(/"/g, '""') + '"' : t;
      };
      const lines = ['id,name,email,joined,blocked,storage_limit_mb,used_mb,live_shares,shares_ever'];
      for (const u of rows) {
        lines.push([u.id, u.name, u.email, new Date(u.created_at).toISOString(), u.banned ? 'yes' : 'no', u.quota_mb,
          (Number(u.used_bytes) / 1048576).toFixed(1), u.live_shares, u.total_shares].map(cell).join(','));
      }
      res.setHeader('Content-Type', 'text/csv; charset=utf-8');
      res.setHeader('Content-Disposition', `attachment; filename="sendit-users-${new Date().toISOString().slice(0, 10)}.csv"`);
      res.send('\ufeff' + lines.join('\r\n'));
    } catch (err) { serverError(res, 'Admin CSV error', err); }
  });

  app.get('/admin/users/:id', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      const u = (await pool.query('SELECT * FROM users WHERE id = $1', [id])).rows[0];
      if (!u) return res.redirect(303, '/admin/users?msg=' + encodeURIComponent('User not found.'));
      const used = Number((await pool.query(`SELECT COALESCE(SUM(f.size), 0)::bigint AS used FROM share_files f JOIN shares s ON s.id = f.share_id
        WHERE s.sender_user_id = $1 AND s.expires_at > now()`, [id])).rows[0].used);
      const sent = (await pool.query(`${SHARE_SELECT} WHERE s.sender_user_id = $1 AND s.expires_at > now() GROUP BY s.id, u.email, ru.email ORDER BY s.created_at DESC`, [id])).rows;
      const got = (await pool.query(`${SHARE_SELECT} WHERE s.recipient_user_id = $1 AND s.expires_at > now() GROUP BY s.id, u.email, ru.email ORDER BY s.created_at DESC`, [id])).rows;
      const here = `/admin/users/${id}`;
      const quotaMb = u.quota_mb ?? settings.quotaMb();
      res.type('html').send(layout(u.name, 'users', `
        ${flash(req)}
        <p><a href="/admin/users">← Users</a></p>
        <h1 class="title">${esc(u.name)} ${u.banned ? '<span class="tag red">blocked</span>' : ''}</h1>
        <p class="hint">${esc(u.email)} · joined ${when(u.created_at)} · ${bytes(used)} of ${bytes(quotaMb * 1048576)} online</p>
        <div class="panel">
          <form method="post" action="${here}/update" class="grid">${hidden}
            <label>Name<input name="name" value="${esc(u.name)}" maxlength="60" required></label>
            <label>Email<input name="email" type="email" value="${esc(u.email)}" required></label>
            <label>Storage limit (MB)<input name="quota" type="number" min="0" max="1000000" value="${u.quota_mb ?? ''}" placeholder="default ${settings.quotaMb()}"></label>
            <label class="check"><input type="checkbox" name="banned" value="1" ${u.banned ? 'checked' : ''}> Blocked (can't sign in or send)</label>
            <div><button class="primary">Save changes</button></div>
          </form>
        </div>
        ${await (async () => {
          const all = (await pool.query('SELECT id, name FROM plans ORDER BY sort, quota_mb')).rows;
          const l = await plans.limitsOf(id);
          return `<div class="panel"><b>Plan:</b> ${esc(l.plan)}${l.until ? ` until ${when(l.until)}` : ''} · ${bytes(l.quotaMb * 1048576)} storage, ${l.maxFileMb} MB per file
            <form method="post" action="${here}/plan" class="search" style="margin:10px 0 0">${hidden}
              <select name="plan"><option value="">Free (no plan)</option>${all.map((p) => `<option value="${p.id}" ${p.id === u.plan_id ? 'selected' : ''}>${esc(p.name)}</option>`).join('')}</select>
              <input name="days" type="number" min="1" max="3650" value="30" style="max-width:110px"> <span class="hint" style="align-self:center">days from now</span>
              <button class="primary">Set plan</button></form></div>`;
        })()}
        <div class="actions-row">
          ${button(`${here}/reset-password`, 'Give a new password', { confirm: `Give ${u.email} a new temporary password?`, fields: backField(here) })}
          ${button(`${here}/delete-shares`, 'Delete all their shares', { cls: 'danger', confirm: `Delete every share made by ${u.email}?`, type: 'DELETE', fields: backField(here) })}
          ${button(`${here}/delete`, 'Delete user', { cls: 'danger', confirm: `Delete ${u.email} and all their files? This cannot be undone.`, type: u.email, fields: backField('/admin/users') })}
        </div>
        <div class="panel">
          <b>Network:</b> last IP <span class="code">${esc(u.last_ip || '—')}</span> · signed up from <span class="code">${esc(u.signup_ip || '—')}</span>
            · app version ${esc(u.app_version || '—')} · last seen ${u.last_seen ? when(u.last_seen) : 'never'}
          ${req.admin.role === 'owner' && u.last_ip ? button('/admin/security/ban', `Ban IP ${esc(u.last_ip)}`, { cls: 'danger', confirm: `Block every request from ${u.last_ip}?`,
            fields: `<input type="hidden" name="ip" value="${esc(u.last_ip)}"><input type="hidden" name="reason" value="${esc('user ' + u.email)}">` + backField(here) }) : ''}
        </div>
        <form method="post" action="${here}/message" class="panel search">${hidden}
          <input name="message" placeholder="Send ${esc(u.name)} a message (shows in their app, they can reply)" maxlength="2000" required>
          <button class="primary">Send</button></form>
        <h2>Shared by ${esc(u.name)} (${sent.length})</h2>${shareTable(sent)}
        <h2>Sent to ${esc(u.name)} (${got.length})</h2>${shareTable(got)}
        <h2>Timeline</h2><div class="panel">${(await userTimeline(id)).map((e) => `<div class="tl"><small>${when(e.at)}</small><span>${e.html}</span></div>`).join('') || '<small>Nothing yet.</small>'}</div>`));
    } catch (err) { serverError(res, 'Admin user page error', err); }
  });

  app.post('/admin/users/:id/update', requireAdmin, async (req, res) => {
    const id = parseInt(req.params.id, 10);
    const back = `/admin/users/${id}`;
    try {
      const name = String(req.body.name || '').trim().slice(0, 60);
      const email = String(req.body.email || '').trim().toLowerCase();
      const raw = String(req.body.quota || '').trim();
      const quota = raw === '' ? null : Math.max(0, Math.min(1000000, parseInt(raw, 10) || 0));
      const banned = req.body.banned === '1';
      if (!name || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.redirect(303, `${back}?msg=` + encodeURIComponent('Name and a valid email are required.'));
      await pool.query('UPDATE users SET name = $1, email = $2, quota_mb = $3, banned = $4 WHERE id = $5', [name, email, quota, banned, id]);
      res.redirect(303, `${back}?msg=` + encodeURIComponent('Saved.'));
    } catch (err) {
      if (err.code === '23505') return res.redirect(303, `${back}?msg=` + encodeURIComponent('Another account already uses that email.'));
      serverError(res, 'Admin update user error', err);
    }
  });

  /** Everything a user did, newest first. */
  async function userTimeline(id) {
    const r = await pool.query(`
      (SELECT at, kind AS what, bytes::text AS a, NULL AS b FROM events WHERE user_id = $1 AND kind IN ('signup', 'redeem', 'login_fail', 'active'))
      UNION ALL (SELECT s.created_at, 'share', s.code, (SELECT COUNT(*)::text FROM share_files f WHERE f.share_id = s.id) FROM shares s WHERE s.sender_user_id = $1)
      UNION ALL (SELECT d.at, 'downloaded', s.code, NULL FROM share_downloads d JOIN shares s ON s.id = d.share_id WHERE d.user_id = $1)
      UNION ALL (SELECT created_at, CASE WHEN from_admin THEN 'support_admin' ELSE 'support_user' END, LEFT(body, 120), NULL FROM support_messages WHERE user_id = $1)
      UNION ALL (SELECT created_at, 'crash', app_version, device FROM crash_reports WHERE user_id = $1)
      UNION ALL (SELECT vu.at, 'code', vu.code, NULL FROM voucher_uses vu WHERE vu.user_id = $1)
      UNION ALL (SELECT at, 'admin', admin_name, action FROM admin_log WHERE action LIKE '% /admin/users/' || $1 || '/%')
      ORDER BY 1 DESC LIMIT 150`, [id]);
    const text = {
      signup: () => '🆕 Created the account', active: () => '📱 Opened the app', login_fail: () => '<span class="red">🔑 Wrong password</span>',
      redeem: () => '⭐ Started a plan', share: (e) => `⬆️ Sent files: <a href="/admin/shares/${esc(e.a)}">${esc(e.a)}</a> (${esc(e.b)} file(s))`,
      downloaded: (e) => `⬇️ Downloaded <a href="/admin/shares/${esc(e.a)}">${esc(e.a)}</a>`,
      support_user: (e) => `💬 Wrote to support: “${esc(e.a)}”`, support_admin: (e) => `🛟 Admin wrote: “${esc(e.a)}”`,
      crash: (e) => `💥 App crashed (v${esc(e.a)}, ${esc(e.b)})`, code: (e) => `🎟 Used code <span class="code">${esc(e.a)}</span>`,
      admin: (e) => `🛡 ${esc(e.a)}: ${esc(e.b)}`
    };
    return r.rows.map((e) => ({ at: e.at, html: (text[e.what] || (() => esc(e.what)))(e) }));
  }

  async function messageUser(userId, body) {
    await pool.query('INSERT INTO support_messages (user_id, from_admin, body, seen_by_admin) VALUES ($1, TRUE, $2, TRUE)', [userId, body]);
    await pool.query(`INSERT INTO notices (user_id, kind, body) VALUES ($1, 'support', $2)`, [userId, body]);
  }

  app.post('/admin/users/:id/message', requireAdmin, async (req, res) => {
    const id = parseInt(req.params.id, 10);
    try {
      const body = String(req.body.message || '').trim().slice(0, 2000);
      if (body) await messageUser(id, body);
      res.redirect(303, `/admin/users/${id}?msg=` + encodeURIComponent(body ? 'Message sent. It shows in their app and in Support.' : 'Write a message first.'));
    } catch (err) { serverError(res, 'Admin message error', err); }
  });

  app.post('/admin/users/bulk', requireAdmin, async (req, res) => {
    try {
      const ids = [].concat(req.body.ids || []).map((x) => parseInt(x, 10)).filter(Boolean).slice(0, 500);
      const action = String(req.body.action || '');
      const owner = req.admin.role === 'owner';
      if (!ids.length) return res.redirect(303, '/admin/users?msg=' + encodeURIComponent('Tick some users first.'));
      let msg;
      if ((action === 'block' || action === 'unblock') && req.admin.role === 'support') return res.status(403).send('Not allowed.');
      if (action === 'block' || action === 'unblock') {
        await pool.query('UPDATE users SET banned = $2 WHERE id = ANY($1)', [ids, action === 'block']);
        msg = `${ids.length} user(s) ${action === 'block' ? 'blocked' : 'unblocked'}.`;
      } else if (action === 'message') {
        const body = String(req.body.message || '').trim().slice(0, 2000);
        if (!body) return res.redirect(303, '/admin/users?msg=' + encodeURIComponent('Write a message first.'));
        for (const id of ids) await messageUser(id, body);
        msg = `Message sent to ${ids.length} user(s).`;
      } else if (action === 'plan' && owner) {
        const planId = parseInt(req.body.plan, 10);
        const days = Math.max(1, Math.min(3650, parseInt(req.body.days, 10) || 30));
        for (const id of ids) await plans.givePlan(pool, id, planId, days);
        msg = `Plan given to ${ids.length} user(s) for ${days} days.`;
      } else if (action === 'delete' && owner) {
        for (const id of ids) await deleteUserCompletely(pool, UPLOAD_DIR, id);
        msg = `${ids.length} account(s) deleted.`;
      } else {
        return res.status(403).send('Not allowed.');
      }
      res.redirect(303, '/admin/users?msg=' + encodeURIComponent(msg));
    } catch (err) { serverError(res, 'Admin bulk error', err); }
  });

  app.post('/admin/users/:id/plan', requireAdmin, async (req, res) => {
    const id = parseInt(req.params.id, 10);
    try {
      const planId = parseInt(req.body.plan, 10) || null;
      const days = Math.max(1, Math.min(3650, parseInt(req.body.days, 10) || 30));
      await pool.query('UPDATE users SET plan_id = $1, plan_until = $2 WHERE id = $3',
        [planId, planId ? new Date(Date.now() + days * 86400000) : null, id]);
      res.redirect(303, `/admin/users/${id}?msg=` + encodeURIComponent(planId ? `Plan set for ${days} days.` : 'Back on the free plan.'));
    } catch (err) { serverError(res, 'Admin set plan error', err); }
  });

  // ---------------- plans & voucher codes ----------------

  app.get('/admin/plans', requireAdmin, async (req, res) => {
    try {
      const all = (await pool.query(`SELECT p.*, (SELECT COUNT(*) FROM users u WHERE u.plan_id = p.id AND u.plan_until > now())::int AS members
        FROM plans p ORDER BY sort, quota_mb`)).rows;
      const vouchers = (await pool.query(`SELECT v.*, p.name AS plan, u.email FROM vouchers v JOIN plans p ON p.id = v.plan_id
        LEFT JOIN users u ON u.id = v.used_by ORDER BY v.created_at DESC LIMIT 200`)).rows;
      const planForm = (p) => `<form method="post" action="/admin/plans/save" class="grid">${hidden}
          <input type="hidden" name="id" value="${p ? p.id : ''}">
          <label>Name<input name="name" value="${esc(p ? p.name : '')}" required maxlength="40" placeholder="e.g. Plus"></label>
          <label>Storage (MB)<input name="quota" type="number" min="1" value="${p ? p.quota_mb : 20480}" required></label>
          <label>Biggest file (MB)<input name="maxFile" type="number" min="1" value="${p ? p.max_file_mb : 4096}" required></label>
          <label>Longest link<select name="ttl">${[[168, '7 days'], [720, '30 days']].map(([h, t]) => `<option value="${h}" ${p && p.max_ttl_hours === h ? 'selected' : (!p && h === 720 ? 'selected' : '')}>${t}</option>`).join('')}</select></label>
          <label>Price text shown in the app<input name="price" value="${esc(p ? p.price_text || '' : '')}" maxlength="120" placeholder="e.g. 99,000 Toman / month"></label>
          <label>Price as a number (for Sales)<input name="amount" type="number" min="0" value="${p ? p.price_amount : 0}"></label>
          <label>Order<input name="sort" type="number" value="${p ? p.sort : 0}"></label>
          <label class="check"><input type="checkbox" name="active" value="1" ${!p || p.active ? 'checked' : ''}> Shown in the app</label>
          <div><button class="primary">${p ? 'Save' : 'Add plan'}</button></div>
        </form>`;
      res.type('html').send(layout('Plans', 'plans', `
        ${flash(req)}
        <h1 class="title">Plans</h1>
        <p class="hint">Free users get ${bytes(settings.quotaMb() * 1048576)} storage and ${settings.maxFileMb()} MB per file (Settings).
          People buy a plan from you (Telegram, card-to-card…), you give them a <b>code</b>, they enter it in the app (Settings → Sendit Plus).</p>
        ${all.map((p) => `<div class="panel"><b>${esc(p.name)}</b> <small>${p.members} active member(s)${p.active ? '' : ' · hidden'}</small>${planForm(p)}</div>`).join('')}
        <details class="panel"><summary>Add a plan</summary>${planForm(null)}</details>

        <h2>How to buy (text shown in the app)</h2>
        <form method="post" action="/admin/plans/buyinfo" class="panel">${hidden}
          <textarea name="buyInfo" rows="3" style="width:100%" maxlength="600">${esc(settings.buyInfo())}</textarea>
          <p><button class="primary">Save</button></p></form>

        <h2>Make codes</h2>
        <form method="post" action="/admin/plans/vouchers" class="panel grid">${hidden}
          <label>Plan<select name="plan">${all.map((p) => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select></label>
          <label>Days<input name="days" type="number" min="1" max="3650" value="30"></label>
          <label>How many codes<input name="count" type="number" min="1" max="200" value="1"></label>
          <label>People per code<input name="maxUses" type="number" min="1" max="100000" value="1" title="More than 1 = a promo code many people can use"></label>
          <label>Money received per use (for Sales)<input name="value" type="number" min="0" value="" placeholder="plan price"></label>
          <label>Own code text (optional)<input name="custom" maxlength="20" placeholder="e.g. NOROOZ1405 (makes 1 code)"></label>
          <label>Note (who/why)<input name="note" maxlength="120" placeholder="optional"></label>
          <div><button class="primary">Make codes</button></div>
        </form>
        ${req.query.codes ? `<div class="panel"><b>New codes</b> (copy and send them):<pre>${esc(req.query.codes)}</pre></div>` : ''}

        <h2>Free trial for new accounts</h2>
        <form method="post" action="/admin/plans/trial" class="panel search">${hidden}
          <select name="plan"><option value="">Off</option>${all.map((p) => `<option value="${p.id}" ${String(p.id) === settings.get('trialPlanId') ? 'selected' : ''}>${esc(p.name)}</option>`).join('')}</select>
          <input name="days" type="number" min="1" max="365" value="${settings.int('trialDays', 7)}" style="max-width:100px"><span class="hint" style="align-self:center">days, given right after sign-up</span>
          <button class="primary">Save</button></form>

        <h2>Codes <small>${vouchers.filter((v) => v.uses < v.max_uses).length} still usable</small></h2>
        <table><tr><th>Code</th><th>Plan</th><th>Made</th><th>Used</th></tr>
        ${vouchers.map((v) => `<tr><td class="code">${esc(v.code)}<br><small>${esc(v.note || '')}${v.value ? ` · ${v.value.toLocaleString()}` : ''}</small></td><td>${esc(v.plan)} · ${v.days} days</td>
          <td><small>${when(v.created_at)}</small></td>
          <td>${v.max_uses > 1 ? `${v.uses} / ${v.max_uses} people` : v.used_by ? `<a href="/admin/users/${v.used_by}">${esc(v.email || 'deleted user')}</a><br><small>${when(v.used_at)}</small>` : 'not yet'}
            ${v.uses === 0 ? button(`/admin/plans/vouchers/${encodeURIComponent(v.code)}/delete`, 'Delete', { cls: 'danger', confirm: 'Delete this unused code?' }) : ''}</td></tr>`).join('')
          || '<tr><td colspan="4"><small>No codes yet.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin plans error', err); }
  });

  app.post('/admin/plans/save', requireAdmin, async (req, res) => {
    try {
      const n = (v, d, lo, hi) => Math.max(lo, Math.min(hi, parseInt(v, 10) || d));
      const vals = [String(req.body.name || '').trim().slice(0, 40) || 'Plan', n(req.body.quota, 20480, 1, 10000000), n(req.body.maxFile, 4096, 1, 1000000),
        [168, 720].includes(parseInt(req.body.ttl, 10)) ? parseInt(req.body.ttl, 10) : 720, String(req.body.price || '').slice(0, 120),
        n(req.body.sort, 0, -1000, 1000), req.body.active === '1', n(req.body.amount, 0, 0, 2000000000)];
      const id = parseInt(req.body.id, 10);
      if (id) await pool.query('UPDATE plans SET name=$1, quota_mb=$2, max_file_mb=$3, max_ttl_hours=$4, price_text=$5, sort=$6, active=$7, price_amount=$8 WHERE id=$9', [...vals, id]);
      else await pool.query('INSERT INTO plans (name, quota_mb, max_file_mb, max_ttl_hours, price_text, sort, active, price_amount) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)', vals);
      res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('Plan saved.'));
    } catch (err) { serverError(res, 'Admin save plan error', err); }
  });

  app.post('/admin/plans/buyinfo', requireAdmin, async (req, res) => {
    try {
      await settings.set('buyInfo', String(req.body.buyInfo || '').trim().slice(0, 600));
      res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('Saved.'));
    } catch (err) { serverError(res, 'Admin buy info error', err); }
  });

  app.post('/admin/plans/vouchers', requireAdmin, async (req, res) => {
    try {
      const planId = parseInt(req.body.plan, 10);
      const plan = (await pool.query('SELECT price_amount FROM plans WHERE id = $1', [planId])).rows[0];
      if (!plan) return res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('Pick a plan.'));
      const days = Math.max(1, Math.min(3650, parseInt(req.body.days, 10) || 30));
      const maxUses = Math.max(1, Math.min(100000, parseInt(req.body.maxUses, 10) || 1));
      const value = String(req.body.value || '').trim() === '' ? plan.price_amount : Math.max(0, parseInt(req.body.value, 10) || 0);
      const note = String(req.body.note || '').slice(0, 120) || null;
      const custom = String(req.body.custom || '').toUpperCase().replace(/[^0-9A-Z]/g, '');
      const codes = [];
      if (custom) {
        if (custom.length < 4 || custom.length > 20) return res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('Own code text must be 4 to 20 letters/numbers.'));
        const taken = (await pool.query(`SELECT 1 FROM vouchers WHERE REPLACE(code, '-', '') = $1`, [custom])).rowCount;
        if (taken) return res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('That code already exists.'));
        await pool.query('INSERT INTO vouchers (code, plan_id, days, note, max_uses, value) VALUES ($1, $2, $3, $4, $5, $6)', [custom, planId, days, note, maxUses, value]);
        codes.push(custom);
      } else {
        const count = Math.max(1, Math.min(200, parseInt(req.body.count, 10) || 1));
        while (codes.length < count) {
          const c = plans.newVoucherCode();
          const r = await pool.query('INSERT INTO vouchers (code, plan_id, days, note, max_uses, value) VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING',
            [c, planId, days, note, maxUses, value]);
          if (r.rowCount) codes.push(c);
        }
      }
      res.redirect(303, '/admin/plans?msg=' + encodeURIComponent(`${codes.length} code(s) made.`) + '&codes=' + encodeURIComponent(codes.join('\n')));
    } catch (err) { serverError(res, 'Admin vouchers error', err); }
  });

  app.post('/admin/plans/trial', requireAdmin, async (req, res) => {
    try {
      await settings.set('trialPlanId', String(parseInt(req.body.plan, 10) || ''));
      await settings.set('trialDays', String(Math.max(1, Math.min(365, parseInt(req.body.days, 10) || 7))));
      res.redirect(303, '/admin/plans?msg=' + encodeURIComponent(settings.get('trialPlanId') ? 'New accounts now get a free trial.' : 'Free trial is off.'));
    } catch (err) { serverError(res, 'Admin trial error', err); }
  });

  app.post('/admin/plans/vouchers/:code/delete', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM vouchers WHERE code = $1 AND uses = 0', [req.params.code]);
      res.redirect(303, '/admin/plans?msg=' + encodeURIComponent('Code deleted.'));
    } catch (err) { serverError(res, 'Admin delete voucher error', err); }
  });

  // ---------------- live ----------------

  app.get('/admin/live.json', requireAdmin, async (req, res) => {
    try {
      let disk = null;
      try { const st = fs.statfsSync(UPLOAD_DIR); disk = { total: st.blocks * st.bsize, free: st.bavail * st.bsize }; } catch { /* old Node */ }
      const online = (await pool.query(`SELECT id, name, email FROM users WHERE last_seen > now() - interval '5 minutes' ORDER BY last_seen DESC LIMIT 50`)).rows;
      const uploading = (await pool.query(`SELECT s.code, s.sender_name, COUNT(f.id)::int AS files, COALESCE(SUM(f.size), 0)::bigint AS size,
          ARRAY_AGG(f.storage_path) AS paths FROM shares s JOIN share_files f ON f.share_id = s.id
          WHERE s.status <> 'ready' AND s.expires_at > now() AND s.created_at > now() - interval '1 day'
          GROUP BY s.id ORDER BY s.created_at DESC LIMIT 10`)).rows.map((r) => {
        let done = 0;
        for (const pth of r.paths) { try { done += fs.statSync(path.join(UPLOAD_DIR, path.basename(pth))).size; } catch { /* not started */ } }
        return { code: r.code, sender: r.sender_name, files: r.files, size: Number(r.size), done };
      });
      const feed = (await pool.query(`SELECT e.kind, e.bytes, e.at, u.name FROM events e LEFT JOIN users u ON u.id = e.user_id
          ORDER BY e.at DESC LIMIT 25`)).rows;
      const nowMin = Math.floor(Date.now() / 60000);
      const series = [];
      for (let m = nowMin - 29; m <= nowMin; m++) {
        const x = traffic.minutes.find((t) => t.m === m);
        series.push({ m, bytesIn: x ? x.bytesIn : 0, bytesOut: x ? x.bytesOut : 0, requests: x ? x.requests : 0 });
      }
      res.json({
        at: new Date(), online, uploading, feed, series,
        activeUploads: Math.max(0, traffic.uploads), activeDownloads: Math.max(0, traffic.downloads),
        disk, memory: { rss: process.memoryUsage().rss, total: os.totalmem(), free: os.freemem() },
        load: os.loadavg(), cpus: os.cpus().length, uptime: process.uptime()
      });
    } catch (err) { serverError(res, 'Admin live error', err); }
  });

  app.get('/admin/live', requireAdmin, (req, res) => {
    res.type('html').send(layout('Live', 'live', `
      <h1 class="title"><span class="dot"></span> Live <small class="hint" id="at"></small></h1>
      <div class="cards">
        <div class="card"><div class="k">Online now</div><div class="v" id="online">–</div><div class="s">signed in, last 5 min</div></div>
        <div class="card"><div class="k">Uploading now</div><div class="v" id="up">–</div><div class="s">connections</div></div>
        <div class="card"><div class="k">Downloading now</div><div class="v" id="down">–</div><div class="s">connections</div></div>
        <div class="card"><div class="k">Server</div><div class="v" id="cpu">–</div><div class="s" id="mem"></div></div>
        <div class="card"><div class="k">Disk</div><div class="v" id="disk">–</div><div class="bar"><i id="diskbar"></i></div></div>
      </div>
      <h2>Traffic, last 30 minutes <small>blue = in (uploads) · green = out (downloads)</small></h2>
      <div class="panel"><svg id="traffic" viewBox="0 0 600 120" preserveAspectRatio="none" style="width:100%;height:140px"></svg>
        <div class="hint" id="tsum"></div></div>
      <div class="two">
        <div><h2>Online</h2><table id="onlineT"></table><h2>Uploads in progress</h2><table id="upT"></table></div>
        <div><h2>What's happening</h2><table id="feedT"></table></div>
      </div>
      <style>.dot{display:inline-block;width:10px;height:10px;border-radius:50%;background:#4EDEA3;box-shadow:0 0 0 0 #4EDEA3;animation:p 1.6s infinite}
        @keyframes p{0%{box-shadow:0 0 0 0 #4EDEA399}100%{box-shadow:0 0 0 12px #4EDEA300}}</style>
      <script>
      const B=(n)=>{n=+n||0;if(n<1024)return n+' B';const u=['KB','MB','GB','TB'];let v=n/1024,i=0;while(v>=1024&&i<3){v/=1024;i++}return v.toFixed(v>=100?0:1)+' '+u[i]};
      const E=(s)=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
      const ago=(d)=>{const s=Math.round((Date.now()-new Date(d))/1000);return s<60?s+'s ago':s<3600?Math.round(s/60)+' min ago':Math.round(s/3600)+' h ago'};
      const kinds={signup:'🆕 signed up',share:'⬆️ sent files',download:'⬇️ files downloaded',redeem:'⭐ started a plan'};
      async function tick(){
        try{
          const r=await fetch('/admin/live.json',{cache:'no-store'});if(!r.ok)return;const d=await r.json();
          at.textContent='updated '+new Date(d.at).toLocaleTimeString();
          online.textContent=d.online.length;up.textContent=d.activeUploads;down.textContent=d.activeDownloads;
          cpu.textContent='load '+d.load[0].toFixed(2);mem.textContent=d.cpus+' CPU · RAM '+B(d.memory.total-d.memory.free)+' / '+B(d.memory.total)+' · app '+B(d.memory.rss)+' · up '+Math.round(d.uptime/3600)+' h';
          if(d.disk){const p=Math.round((d.disk.total-d.disk.free)/d.disk.total*100);disk.textContent=B(d.disk.free)+' free';diskbar.style.width=p+'%'}
          const max=Math.max(1,...d.series.map(x=>Math.max(x.bytesIn,x.bytesOut)));const w=600/d.series.length;
          traffic.innerHTML=d.series.map((x,i)=>'<rect x="'+(i*w+1)+'" y="'+(120-x.bytesIn/max*115)+'" width="'+(w/2-1)+'" height="'+(x.bytesIn/max*115)+'" fill="#4F46E5"><title>in '+B(x.bytesIn)+'</title></rect>'+
            '<rect x="'+(i*w+w/2)+'" y="'+(120-x.bytesOut/max*115)+'" width="'+(w/2-1)+'" height="'+(x.bytesOut/max*115)+'" fill="#4EDEA3"><title>out '+B(x.bytesOut)+'</title></rect>').join('');
          const tin=d.series.reduce((a,x)=>a+x.bytesIn,0),tout=d.series.reduce((a,x)=>a+x.bytesOut,0),req=d.series.reduce((a,x)=>a+x.requests,0);
          tsum.textContent='In '+B(tin)+' · out '+B(tout)+' · '+req+' requests';
          onlineT.innerHTML=d.online.map(u=>'<tr><td><a href="/admin/users/'+u.id+'">'+E(u.name)+'</a><br><small>'+E(u.email)+'</small></td></tr>').join('')||'<tr><td><small>Nobody right now.</small></td></tr>';
          upT.innerHTML=d.uploading.map(u=>{const p=u.size?Math.round(u.done/u.size*100):0;return '<tr><td class="code"><a href="/admin/shares/'+u.code+'">'+u.code+'</a></td><td>'+E(u.sender)+'<br><small>'+u.files+' file(s) · '+B(u.done)+' / '+B(u.size)+'</small><div class="bar small"><i style="width:'+p+'%"></i></div></td></tr>'}).join('')||'<tr><td><small>No uploads running.</small></td></tr>';
          feedT.innerHTML=d.feed.map(e=>'<tr><td>'+(kinds[e.kind]||E(e.kind))+'</td><td>'+E(e.name||'someone')+(e.bytes>0?' <small>'+B(e.bytes)+'</small>':'')+'</td><td><small>'+ago(e.at)+'</small></td></tr>').join('')||'<tr><td><small>Nothing yet.</small></td></tr>';
        }catch(e){}
      }
      tick();setInterval(tick,3000);
      </script>`));
  });

  app.post('/admin/users/:id/reset-password', requireAdmin, async (req, res) => {
    try {
      const temp = crypto.randomBytes(6).toString('base64url');
      const r = await pool.query('UPDATE users SET password_hash = $1 WHERE id = $2 RETURNING email', [await bcrypt.hash(temp, 10), parseInt(req.params.id, 10)]);
      if (!r.rows[0]) return goBack(req, res, 'User not found.');
      goBack(req, res, `New password for ${r.rows[0].email}: ${temp} (give it to them; they can change it with "Forgot password").`);
    } catch (err) { serverError(res, 'Admin reset error', err); }
  });

  app.post('/admin/users/:id/delete-shares', requireAdmin, async (req, res) => {
    try {
      const r = await pool.query('SELECT id FROM shares WHERE sender_user_id = $1', [parseInt(req.params.id, 10)]);
      for (const row of r.rows) await deleteShare(row.id);
      goBack(req, res, `Deleted ${r.rows.length} share(s).`);
    } catch (err) { serverError(res, 'Admin delete shares error', err); }
  });

  app.post('/admin/users/:id/delete', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      const r = await pool.query('SELECT email FROM users WHERE id = $1', [id]);
      if (!r.rows[0]) return goBack(req, res, 'User not found.', '/admin/users');
      await deleteUserCompletely(pool, UPLOAD_DIR, id);
      goBack(req, res, `Deleted ${r.rows[0].email} and all their files.`, '/admin/users');
    } catch (err) { serverError(res, 'Admin delete user error', err); }
  });

  // ---------------- shares ----------------

  app.get('/admin/shares', requireAdmin, async (req, res) => {
    try {
      const q = String(req.query.q || '').trim();
      const filter = String(req.query.filter || 'live');
      const like = `%${q.toLowerCase()}%`;
      const conds = {
        live: `s.expires_at > now() AND s.status = 'ready'`,
        uploading: `s.expires_at > now() AND s.status <> 'ready'`,
        locked: `s.expires_at > now() AND s.password_hash IS NOT NULL`,
        person: `s.expires_at > now() AND s.recipient_user_id IS NOT NULL`,
        onetime: `s.expires_at > now() AND s.one_time`
      };
      const where = conds[filter] || conds.live;
      const sort = req.query.sort === 'new' ? 's.created_at DESC' : 'size DESC';
      const rows = (await pool.query(`${SHARE_SELECT}
        WHERE ${where} AND ($1 = '' OR s.code = UPPER($1) OR LOWER(s.sender_name) LIKE $2 OR LOWER(COALESCE(u.email, '')) LIKE $2
              OR EXISTS (SELECT 1 FROM share_files f2 WHERE f2.share_id = s.id AND LOWER(f2.name) LIKE $2))
        GROUP BY s.id, u.email, ru.email ORDER BY ${sort} LIMIT 300`, [q, like])).rows;
      const opt = (v, l) => `<option value="${v}" ${filter === v ? 'selected' : ''}>${l}</option>`;
      res.type('html').send(layout('Shares', 'shares', `
        ${flash(req)}
        <form class="search" action="/admin/shares"><input name="q" value="${esc(q)}" placeholder="Code, file name or sender">
          <select name="filter">${opt('live', 'Live')}${opt('uploading', 'Still uploading')}${opt('locked', 'With password')}${opt('person', 'Sent to a person')}${opt('onetime', 'One-time')}</select>
          <select name="sort"><option value="size">Biggest first</option><option value="new" ${req.query.sort === 'new' ? 'selected' : ''}>Newest first</option></select>
          <button>Show</button></form>
        <p class="hint">${rows.length} share(s) · ${bytes(rows.reduce((a, s) => a + Number(s.size), 0))}</p>
        ${shareTable(rows)}`));
    } catch (err) { serverError(res, 'Admin shares error', err); }
  });

  app.get('/admin/shares/:code', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      if (!s) return res.redirect(303, '/admin/shares?msg=' + encodeURIComponent('Share not found (it may have expired).'));
      const files = (await pool.query('SELECT * FROM share_files WHERE share_id = $1 ORDER BY idx', [s.id])).rows;
      const sender = s.sender_user_id ? (await pool.query('SELECT id, name, email FROM users WHERE id = $1', [s.sender_user_id])).rows[0] : null;
      const to = s.recipient_user_id ? (await pool.query('SELECT id, name, email FROM users WHERE id = $1', [s.recipient_user_id])).rows[0] : null;
      const here = `/admin/shares/${s.code}`;
      const total = files.reduce((a, f) => a + Number(f.size), 0);

      const gallery = [];
      const previews = await Promise.all(files.map(async (f) => {
        const url = `/admin/file/${s.code}/${f.idx}`;
        const kind = kindOf(f.name, f.mime);
        let preview = '';
        if (!f.uploaded) preview = '<p class="hint">Not fully uploaded.</p>';
        else if (kind === 'image' || kind === 'video') {
          // Shown in the gallery above; tap for full screen.
          gallery.push(`<a href="${url}?inline=1" data-kind="${kind}" data-name="${esc(f.name)}">${kind === 'image' ? `<img src="${url}?inline=1" loading="lazy" alt="">`
            : `<video src="${url}?inline=1#t=0.5" preload="metadata" muted></video><span class="play"><span>${ui.icon('play')}</span></span>`}<span class="gl">${esc(f.name)}</span></a>`);
        }
        else if (kind === 'audio') preview = `<div class="wave" data-size="${Number(f.size)}"><button type="button" class="wp">${ui.icon('play')}</button><canvas></canvas><audio src="${url}?inline=1" preload="metadata"></audio></div>`;
        else if (kind === 'pdf') preview = `<p><a href="${url}?inline=1" target="_blank">Open the PDF in a new tab</a></p>`;
        else if (kind === 'text') {
          let text = '';
          try {
            const fd = await fs.promises.open(path.join(UPLOAD_DIR, path.basename(f.storage_path)), 'r');
            const buf = Buffer.alloc(16384);
            const { bytesRead } = await fd.read(buf, 0, buf.length, 0);
            await fd.close();
            text = buf.subarray(0, bytesRead).toString('utf8');
          } catch { text = '(could not read the file)'; }
          preview = `<pre>${esc(text)}${Number(f.size) > 16384 ? '\n…' : ''}</pre>`;
        } else preview = '<p class="hint">No preview for this type. Use Download.</p>';
        return `<div class="file"><div class="fhead"><b>${esc(f.name)}</b><small>${bytes(f.size)} · ${esc(f.mime)} · downloaded ${f.completed_downloads}×</small>
          ${f.uploaded ? `<span><a class="btn" href="${url}?inline=1" target="_blank">Open</a> <a class="btn" href="${url}">Download</a>
            ${s.e2e ? '' : button('/admin/banned/add', '🚫 Ban this file', { cls: 'danger', confirm: 'Ban this exact file forever? Every share with it gets blocked, and nobody can share it again.',
              fields: `<input type="hidden" name="code" value="${s.code}"><input type="hidden" name="idx" value="${f.idx}">` + backField(here) })}</span>` : ''}</div>${preview}</div>`;
      }));

      res.type('html').send(layout(`Share ${s.code}`, 'shares', `
        ${flash(req)}
        <p><a href="/admin/shares">← Shares</a></p>
        <h1 class="title">Share <span class="code">${s.code}</span> ${s.password_hash ? '<span class="tag">🔒 password</span>' : ''} ${s.one_time ? '<span class="tag">one-time</span>' : ''}</h1>
        <p class="hint">From ${sender ? `<a href="/admin/users/${sender.id}">${esc(sender.name)}</a> (${esc(sender.email)})` : esc(s.sender_name)}
          ${to ? ` → <a href="/admin/users/${to.id}">${esc(to.name)}</a> (${esc(to.email)})` : ' → anyone with the code'}<br>
          ${files.length} file(s) · ${bytes(total)} · ${s.download_count} download(s) · created ${when(s.created_at)} · ${s.status === 'ready' ? left(s.expires_at) : 'status: ' + esc(s.status)} (${when(s.expires_at)})</p>
        ${s.blocked ? `<div class="note red">⛔ Blocked: ${esc(s.blocked_reason || 'by an admin')}. Nobody can open it.</div>` : ''}
        ${s.e2e ? '<div class="note">🔐 End-to-end encrypted: the files can\'t be previewed (the server never has the key).</div>' : ''}
        ${await (async () => {
          const reps = (await pool.query('SELECT * FROM reports WHERE share_id = $1 ORDER BY created_at DESC', [s.id])).rows;
          return reps.length ? `<div class="panel"><b>🚩 ${reps.length} report(s)</b>${reps.map((r) => `<div class="tl"><small>${when(r.created_at)}</small><span>${esc(r.reason)}${r.details ? ': ' + esc(r.details) : ''} <span class="tag">${esc(r.status)}</span></span></div>`).join('')}</div>` : '';
        })()}
        <div class="actions-row">
          ${s.blocked ? button(`/admin/reports/share/${s.code}/unblock`, 'Unblock', { fields: backField(here) })
            : button(`/admin/reports/share/${s.code}/block`, '⛔ Block (hide)', { cls: 'danger', confirm: 'Hide this share from everyone?', fields: backField(here) })}
          ${button(`${here}/extend`, '+1 hour', { fields: backField(here) + '<input type="hidden" name="hours" value="1">' })}
          ${button(`${here}/extend`, '+1 day', { fields: backField(here) + '<input type="hidden" name="hours" value="24">' })}
          ${button(`${here}/extend`, '+7 days', { fields: backField(here) + '<input type="hidden" name="hours" value="168">' })}
          ${s.password_hash ? button(`${here}/remove-password`, 'Remove password', { confirm: 'Remove the password from this share?', fields: backField(here) }) : ''}
          ${s.one_time ? button(`${here}/not-one-time`, 'Allow many downloads', { fields: backField(here) }) : ''}
          ${button(`${here}/delete`, 'Delete share', { cls: 'danger', confirm: `Delete share ${s.code} and its files?`, fields: backField('/admin/shares') })}
        </div>
        ${gallery.length ? `<div class="gallery">${gallery.join('')}</div>` : ''}
        ${previews.join('')}`));
    } catch (err) { serverError(res, 'Admin share page error', err); }
  });

  // Admin can open/download any file (even password-protected or one-time) without counting a download.
  app.get('/admin/file/:code/:idx', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      if (!s) return res.status(404).send('Share not found.');
      const f = (await pool.query('SELECT * FROM share_files WHERE share_id = $1 AND idx = $2', [s.id, parseInt(req.params.idx, 10)])).rows[0];
      if (!f || !f.storage_path) return res.status(404).send('File not found.');
      const full = path.join(UPLOAD_DIR, path.basename(f.storage_path));
      let size;
      try { size = fs.statSync(full).size; } catch { return res.status(404).send('File data is missing.'); }
      const name = path.posix.basename(f.name);
      const ascii = name.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '_');
      res.setHeader('Content-Type', kindOf(f.name, f.mime) === 'text' ? 'text/plain; charset=utf-8' : (f.mime || 'application/octet-stream'));
      res.setHeader('Content-Disposition', `${req.query.inline ? 'inline' : 'attachment'}; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`);
      res.setHeader('Accept-Ranges', 'bytes');
      res.setHeader('X-Content-Type-Options', 'nosniff');
      res.setHeader('Content-Security-Policy', "default-src 'none'; img-src 'self'; media-src 'self'; style-src 'unsafe-inline'");
      let start = 0, end = size - 1;
      const m = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range || '');
      if (m && size > 0) {
        if (m[1] === '') start = Math.max(0, size - Number(m[2]));
        else { start = Number(m[1]); if (m[2] !== '') end = Math.min(Number(m[2]), size - 1); }
        if (start > end) { res.setHeader('Content-Range', `bytes */${size}`); return res.status(416).end(); }
        res.status(206).setHeader('Content-Range', `bytes ${start}-${end}/${size}`);
      }
      res.setHeader('Content-Length', size === 0 ? 0 : end - start + 1);
      if (size === 0) return res.end();
      fs.createReadStream(full, { start, end }).on('error', (e) => res.destroy(e)).pipe(res);
    } catch (err) { serverError(res, 'Admin file error', err); }
  });

  app.post('/admin/shares/:code/extend', requireAdmin, async (req, res) => {
    try {
      const hours = [1, 24, 168].includes(parseInt(req.body.hours, 10)) ? parseInt(req.body.hours, 10) : 24;
      const s = await findShare(req.params.code);
      if (!s) return goBack(req, res, 'Share not found.', '/admin/shares');
      await pool.query('UPDATE shares SET expires_at = GREATEST(expires_at, now()) + make_interval(hours => $2) WHERE id = $1', [s.id, hours]);
      goBack(req, res, `Extended by ${hours === 1 ? '1 hour' : hours === 24 ? '1 day' : '7 days'}.`);
    } catch (err) { serverError(res, 'Admin extend error', err); }
  });

  app.post('/admin/shares/:code/remove-password', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      if (!s) return goBack(req, res, 'Share not found.', '/admin/shares');
      await pool.query('UPDATE shares SET password_hash = NULL WHERE id = $1', [s.id]);
      goBack(req, res, 'Password removed. Anyone with the code can download now.');
    } catch (err) { serverError(res, 'Admin remove password error', err); }
  });

  app.post('/admin/shares/:code/not-one-time', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      if (!s) return goBack(req, res, 'Share not found.', '/admin/shares');
      await pool.query('UPDATE shares SET one_time = FALSE WHERE id = $1', [s.id]);
      goBack(req, res, 'This share can now be downloaded many times.');
    } catch (err) { serverError(res, 'Admin one-time error', err); }
  });

  app.post('/admin/shares/:code/delete', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      if (!s) return goBack(req, res, 'Share not found.', '/admin/shares');
      await deleteShare(s.id);
      goBack(req, res, `Share ${s.code} deleted.`, '/admin/shares');
    } catch (err) { serverError(res, 'Admin delete share error', err); }
  });

  // ---------------- support chat ----------------

  // Two columns like a messenger: conversations on the left, the chat on the right.
  app.get('/admin/support', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.query.id, 10) || null;
      const tab = ['open', 'solved', 'all'].includes(req.query.tab) ? req.query.tab : 'open';
      const convos = (await pool.query(`
        SELECT u.id, u.name, u.email, u.support_solved, MAX(m.created_at) AS last_at,
               COUNT(*) FILTER (WHERE NOT m.from_admin AND NOT m.seen_by_admin)::int AS unread,
               (ARRAY_AGG(m.body ORDER BY m.id DESC))[1] AS last_body,
               (ARRAY_AGG(m.from_admin ORDER BY m.id DESC))[1] AS last_from_admin
        FROM support_messages m JOIN users u ON u.id = m.user_id
        WHERE ($1 = 'all' OR ($1 = 'solved') = u.support_solved OR u.id = $2)
        GROUP BY u.id ORDER BY unread DESC, last_at DESC LIMIT 300`, [tab, id || 0])).rows;
      let right = `<div class="blank"><div>${ui.icon('inbox')}<p>Pick a conversation.</p></div></div>`;
      if (id) {
        const u = (await pool.query('SELECT id, name, email, support_solved FROM users WHERE id = $1', [id])).rows[0];
        if (u) {
          const msgs = (await pool.query('SELECT from_admin, body, created_at FROM support_messages WHERE user_id = $1 ORDER BY id', [id])).rows;
          await pool.query('UPDATE support_messages SET seen_by_admin = TRUE WHERE user_id = $1 AND NOT from_admin AND NOT seen_by_admin', [id]);
          const replies = (await pool.query('SELECT id, title, body FROM support_replies ORDER BY title')).rows;
          right = `<div class="ih"><a class="iconbtn menu-btn" href="/admin/support?tab=${tab}" title="Back">${ui.icon('chevrons')}</a>
              <span class="avatar">${esc((u.name || '?').slice(0, 1).toUpperCase())}</span>
              <div class="who"><b>${esc(u.name)}</b><br><small>${esc(u.email)}</small></div>
              <a class="btn" href="/admin/users/${u.id}">${ui.icon('user', 'sm')} Profile</a>
              ${button(`/admin/support/${u.id}/solve`, u.support_solved ? 'Reopen' : `${ui.icon('check', 'sm')} Solved`, { cls: u.support_solved ? '' : 'primary', fields: `<input type="hidden" name="tab" value="${tab}">` })}</div>
            <div class="thread">${msgs.map((m) => `<div class="bubble ${m.from_admin ? 'me' : ''}">${esc(m.body)}<small>${when(m.created_at)}</small></div>`).join('')}</div>
            <form method="post" action="/admin/support/${u.id}" class="compose">${hidden}<input type="hidden" name="tab" value="${tab}">
              <textarea name="body" rows="3" maxlength="2000" required placeholder="Write a reply… (Ctrl+Enter sends)" autofocus></textarea>
              <div class="cr"><select data-insert><option value="">Saved replies…</option>${replies.map((r) => `<option value="${esc(r.body)}">${esc(r.title)}</option>`).join('')}</select>
                <a href="#replies" onclick="document.getElementById('replies').open=true">Manage</a><span class="grow"></span>
                <span class="hint">They get a notification</span><button class="primary">${ui.icon('send', 'sm')} Send</button></div></form>`;
        }
      }
      const replies = (await pool.query('SELECT id, title, body FROM support_replies ORDER BY title')).rows;
      res.type('html').send(layout('Support', 'support', `
        ${flash(req)}
        <div class="inbox ${id ? 'has-chat' : ''}">
          <div class="il">
            <div class="ih"><input placeholder="Search people…" aria-label="Search conversations"></div>
            <div class="tabs">${['open', 'solved', 'all'].map((x) => `<a class="${x === tab ? 'on' : ''}" href="/admin/support?tab=${x}">${x[0].toUpperCase() + x.slice(1)}</a>`).join('')}</div>
            <div class="conv">${convos.map((c) => `<a href="/admin/support?id=${c.id}&tab=${tab}" class="${c.id === id ? 'on' : ''} ${c.unread ? 'unread' : ''}">
                <span class="avatar">${esc((c.name || '?').slice(0, 1).toUpperCase())}</span>
                <span class="cm"><div><b>${esc(c.name)}</b>${c.unread ? `<span class="badge">${c.unread}</span>` : ''}${c.support_solved ? `<span class="tag green">solved</span>` : ''}</div>
                <p>${c.last_from_admin ? 'You: ' : ''}${esc(String(c.last_body).slice(0, 90))}</p><small>${when(c.last_at)}</small></span></a>`).join('')
              || `<div class="blank" style="padding:40px"><small>${tab === 'open' ? 'Nothing waiting. 🎉' : 'No conversations.'}</small></div>`}</div>
          </div>
          <div class="ir">${right}</div>
        </div>
        <details class="panel" id="replies"><summary>Saved replies (${replies.length})</summary>
          <table data-plain><tr><th>Title</th><th>Text</th><th></th></tr>${replies.map((r) => `<tr><td><b>${esc(r.title)}</b></td><td>${esc(r.body)}</td>
            <td>${button(`/admin/support/replies/${r.id}/delete`, 'Delete', { cls: 'danger', fields: backField('/admin/support' + (id ? `?id=${id}` : '')) })}</td></tr>`).join('') || '<tr><td colspan="3"><small>None yet.</small></td></tr>'}</table>
          <form method="post" action="/admin/support/replies" class="grid" style="margin-top:12px">${hidden}${backField('/admin/support' + (id ? `?id=${id}` : ''))}
            <label>Title<input name="title" maxlength="60" required placeholder="e.g. How to update"></label>
            <label class="wide">Text<textarea name="body" rows="2" maxlength="2000" required></textarea></label>
            <div><button class="primary">Save reply</button></div></form>
        </details>`));
    } catch (err) { serverError(res, 'Admin support error', err); }
  });

  app.get('/admin/support/:id', requireAdmin, (req, res) => res.redirect(303, `/admin/support?id=${parseInt(req.params.id, 10) || ''}`));

  app.post('/admin/support/replies', requireAdmin, async (req, res) => {
    try {
      const title = String(req.body.title || '').trim().slice(0, 60), body = String(req.body.body || '').trim().slice(0, 2000);
      if (title && body) await pool.query('INSERT INTO support_replies (title, body) VALUES ($1, $2)', [title, body]);
      goBack(req, res, 'Saved reply added.', '/admin/support');
    } catch (err) { serverError(res, 'Saved reply error', err); }
  });

  app.post('/admin/support/replies/:rid/delete', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM support_replies WHERE id = $1', [parseInt(req.params.rid, 10)]);
      goBack(req, res, 'Saved reply deleted.', '/admin/support');
    } catch (err) { serverError(res, 'Saved reply error', err); }
  });

  app.post('/admin/support/:id/solve', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      const r = await pool.query('UPDATE users SET support_solved = NOT support_solved WHERE id = $1 RETURNING support_solved', [id]);
      const tab = String(req.body.tab || 'open');
      res.redirect(303, `/admin/support?id=${id}&tab=${encodeURIComponent(tab)}&msg=` + encodeURIComponent(r.rows[0]?.support_solved ? 'Marked as solved.' : 'Reopened.'));
    } catch (err) { serverError(res, 'Admin solve error', err); }
  });

  app.post('/admin/support/:id', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      const body = String(req.body.body || '').trim().slice(0, 2000);
      const tab = encodeURIComponent(String(req.body.tab || 'open'));
      if (!body) return res.redirect(303, `/admin/support?id=${id}&tab=${tab}`);
      await pool.query('INSERT INTO support_messages (user_id, from_admin, body, seen_by_admin) VALUES ($1, TRUE, $2, TRUE)', [id, body]);
      await pool.query(`INSERT INTO notices (user_id, kind, body) VALUES ($1, 'support', $2)`, [id, body]);
      res.redirect(303, `/admin/support?id=${id}&tab=${tab}&msg=` + encodeURIComponent('Reply sent.'));
    } catch (err) { serverError(res, 'Admin support reply error', err); }
  });

  // ---------------- messages (broadcast) ----------------

  app.get('/admin/messages', requireAdmin, async (req, res) => {
    try {
      const list = (await pool.query('SELECT id, title, body, created_at FROM broadcasts ORDER BY id DESC LIMIT 50')).rows;
      const users = (await pool.query('SELECT COUNT(*)::int AS n FROM users WHERE NOT banned')).rows[0].n;
      res.type('html').send(layout('Messages', 'messages', `
        ${flash(req)}
        <div class="panel">
          <form method="post" action="/admin/messages" class="grid">${hidden}
            <label class="wide">Title<input name="title" maxlength="120" required placeholder="e.g. New version available"></label>
            <label class="wide">Message<textarea name="body" maxlength="1000" rows="4" required placeholder="What do you want to tell everyone?"></textarea></label>
            <div><button class="primary" onclick="return confirm('Send this notification to all ${users} users?')">Send to everyone</button></div>
          </form>
          <p class="hint">Phones show it as a notification the next time Sendit checks (within a few minutes while the app is running, otherwise up to ~15 minutes). Messages older than 7 days aren't delivered.</p>
        </div>
        <h2>Sent messages</h2>
        <table><tr><th>When</th><th>Message</th><th></th></tr>
        ${list.map((b) => `<tr><td><small>${when(b.created_at)}</small></td><td><b>${esc(b.title)}</b><br>${esc(b.body)}</td>
          <td>${button(`/admin/messages/${b.id}/delete`, 'Delete', { cls: 'danger', confirm: 'Delete this message? Phones that already got it keep the notification.' })}</td></tr>`).join('')
          || '<tr><td colspan="3"><small>No messages yet.</small></td></tr>'}
        </table>`));
    } catch (err) { serverError(res, 'Admin messages error', err); }
  });

  app.post('/admin/messages', requireAdmin, async (req, res) => {
    try {
      const title = String(req.body.title || '').trim().slice(0, 120);
      const body = String(req.body.body || '').trim().slice(0, 1000);
      if (!title || !body) return res.redirect(303, '/admin/messages?msg=' + encodeURIComponent('Title and message are required.'));
      await pool.query('INSERT INTO broadcasts (title, body) VALUES ($1, $2)', [title, body]);
      res.redirect(303, '/admin/messages?msg=' + encodeURIComponent('Sent. Phones will show it shortly.'));
    } catch (err) { serverError(res, 'Admin send message error', err); }
  });

  app.post('/admin/messages/:id/delete', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM broadcasts WHERE id = $1', [parseInt(req.params.id, 10)]);
      res.redirect(303, '/admin/messages?msg=' + encodeURIComponent('Deleted.'));
    } catch (err) { serverError(res, 'Admin delete message error', err); }
  });

  // ---------------- crash reports ----------------

  app.get('/admin/crashes', requireAdmin, async (req, res) => {
    try {
      const list = (await pool.query(`
        SELECT c.id, c.app_version, c.device, c.android, c.stack, c.created_at, u.id AS uid, u.email
        FROM crash_reports c LEFT JOIN users u ON u.id = c.user_id ORDER BY c.id DESC LIMIT 200`)).rows;
      // Group identical crashes by their first lines so repeats are easy to spot.
      const groups = new Map();
      for (const c of list) {
        const key = c.stack.split('\n').slice(0, 3).join('\n');
        const g = groups.get(key) || { key, count: 0, latest: c, versions: new Set() };
        g.count++; g.versions.add(c.app_version);
        groups.set(key, g);
      }
      res.type('html').send(layout('Crashes', 'crashes', `
        ${flash(req)}
        <p class="hint">${list.length} report(s) in the last 90 days, ${groups.size} different problem(s). Newest first.</p>
        ${list.length ? `<div class="actions-row">${button('/admin/crashes/clear', 'Delete all reports', { cls: 'danger', confirm: 'Delete all crash reports?' })}</div>` : ''}
        ${[...groups.values()].map((g) => `<div class="file">
          <div class="fhead"><b>${esc(g.key.split('\n')[0].slice(0, 160))}</b>
            <small>${g.count}× · versions ${esc([...g.versions].join(', '))} · last ${when(g.latest.created_at)} · ${esc(g.latest.device)} · Android ${esc(g.latest.android)}
            ${g.latest.email ? ` · <a href="/admin/users/${g.latest.uid}">${esc(g.latest.email)}</a>` : ''}</small></div>
          <details><summary>Full error</summary><pre>${esc(g.latest.stack)}</pre></details></div>`).join('') || '<p class="hint">No crashes reported. 🎉</p>'}`));
    } catch (err) { serverError(res, 'Admin crashes error', err); }
  });

  app.post('/admin/crashes/clear', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM crash_reports');
      res.redirect(303, '/admin/crashes?msg=' + encodeURIComponent('All crash reports deleted.'));
    } catch (err) { serverError(res, 'Admin clear crashes error', err); }
  });

  // ---------------- settings ----------------

  app.get('/admin/settings', requireAdmin, async (req, res) => {
    const s = settings.all();
    const sel = (v, cur, l) => `<option value="${v}" ${String(cur) === String(v) ? 'selected' : ''}>${l}</option>`;
    res.type('html').send(layout('Settings', 'settings', `
      ${flash(req)}
      <div class="panel">
        <form method="post" action="/admin/settings" class="grid">${hidden}
          <label class="wide">Announcement shown at the top of the app (leave empty for none)
            <textarea name="announcement" maxlength="300" rows="3" placeholder="e.g. The server will be updated tonight at 23:00.">${esc(s.announcement)}</textarea></label>
          <label class="check"><input type="checkbox" name="signupsOpen" value="1" ${s.signupsOpen === 'true' ? 'checked' : ''}> New people can create accounts</label>
          <label>Storage per user (MB)<input name="quotaMb" type="number" min="1" max="1000000" value="${esc(s.quotaMb)}"></label>
          <label>Largest file (MB)<input name="maxFileMb" type="number" min="1" max="100000" value="${esc(s.maxFileMb)}"></label>
          <label>Default time a code works<select name="ttlHours">${sel(1, s.ttlHours, '1 hour')}${sel(24, s.ttlHours, '1 day')}${sel(168, s.ttlHours, '7 days')}</select></label>
          <label class="check"><input type="checkbox" name="blockTypesOn" value="1" ${s.blockTypesOn === 'true' ? 'checked' : ''}> Block some file types</label>
          <label class="wide">File types to block (when the switch above is on)<input name="blockedTypes" value="${esc(s.blockedTypes)}" placeholder="exe, apk, bat"></label>
          <div><button class="primary">Save settings</button></div>
        </form>
      </div>
      <h2>Maintenance</h2>
      <div class="actions-row">
        ${button('/admin/cleanup', 'Delete expired files now', { fields: backField('/admin/settings') })}
      </div>
      <p class="hint">Expired files are also deleted automatically every 30 minutes.
        Password-reset email: ${process.env.SMTP_HOST ? 'on' : 'off (add SMTP_HOST, SMTP_USER, SMTP_PASS, SMTP_FROM to .env and restart)'}.</p>`));
  });

  app.post('/admin/settings', requireAdmin, async (req, res) => {
    try {
      await settings.set('announcement', String(req.body.announcement || '').trim().slice(0, 300));
      await settings.set('signupsOpen', req.body.signupsOpen === '1' ? 'true' : 'false');
      const num = (v, min, max, d) => Math.max(min, Math.min(max, parseInt(v, 10) || d));
      await settings.set('quotaMb', num(req.body.quotaMb, 1, 1000000, 2048));
      await settings.set('maxFileMb', num(req.body.maxFileMb, 1, 100000, 1024));
      await settings.set('ttlHours', [1, 24, 168].includes(parseInt(req.body.ttlHours, 10)) ? parseInt(req.body.ttlHours, 10) : 24);
      await settings.set('blockTypesOn', req.body.blockTypesOn === '1' ? 'true' : 'false');
      if (typeof req.body.blockedTypes === 'string') await settings.set('blockedTypes', req.body.blockedTypes.slice(0, 500));
      res.redirect(303, '/admin/settings?msg=' + encodeURIComponent('Settings saved.'));
    } catch (err) { serverError(res, 'Admin settings error', err); }
  });

  // ---------------- per-admin preferences, live pulse, search, notifications ----------------

  async function getPref(admin, key) {
    const r = await pool.query('SELECT value FROM admin_prefs WHERE admin = $1 AND key = $2', [admin.username, key]);
    return r.rows[0] ? r.rows[0].value : null;
  }
  async function setPref(admin, key, value) {
    if (value === null || value === undefined) return pool.query('DELETE FROM admin_prefs WHERE admin = $1 AND key = $2', [admin.username, key]);
    return pool.query(`INSERT INTO admin_prefs (admin, key, value) VALUES ($1, $2, $3)
      ON CONFLICT (admin, key) DO UPDATE SET value = EXCLUDED.value`, [admin.username, key, JSON.stringify(value)]);
  }

  app.post('/admin/me/prefs', requireAdmin, async (req, res) => {
    try {
      const key = String(req.body.key || '');
      if (!['dashboard'].includes(key)) return res.status(400).json({ error: 'Unknown setting.' });
      if (JSON.stringify(req.body.value || '').length > 8000) return res.status(400).json({ error: 'Too big.' });
      await setPref(req.admin, key, req.body.value ?? null);
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Admin prefs error', err); }
  });

  let alertCache = { at: 0, n: 0 };
  const roleOf = (admin) => admin.role;
  const sees = (admin, roles) => roles.split(',').includes(roleOf(admin));

  app.get('/admin/pulse.json', requireAdmin, async (req, res) => {
    try {
      const a = req.admin;
      const can = (path) => security.canOpen(a.role, path);
      const q = async (sql) => (await pool.query(sql)).rows[0].n;
      if (can('/admin/alerts') && Date.now() - alertCache.at > 60000) alertCache = { at: Date.now(), n: (await computeAlerts(pool, settings)).length };
      const read = Number(await getPref(a, 'notifRead')) || 0;
      const notes = (await pool.query(`SELECT id, title, roles FROM admin_notices WHERE id > $1 ORDER BY id DESC LIMIT 50`, [read])).rows.filter((n) => sees(a, n.roles));
      res.json({
        live: {
          online: await q(`SELECT COUNT(*)::int AS n FROM users WHERE last_seen > now() - interval '5 minutes'`),
          uploads: Math.max(0, traffic.uploads), downloads: Math.max(0, traffic.downloads)
        },
        badges: {
          reports: can('/admin/reports') ? await q(`SELECT COUNT(DISTINCT code)::int AS n FROM reports WHERE status = 'open'`) : 0,
          support: can('/admin/support') ? await q(`SELECT COUNT(DISTINCT user_id)::int AS n FROM support_messages WHERE NOT from_admin AND NOT seen_by_admin`) : 0,
          alerts: can('/admin/alerts') ? alertCache.n : 0,
          notif: notes.length
        },
        latest: notes[0] ? { id: Number(notes[0].id), title: notes[0].title } : null
      });
    } catch (err) { serverError(res, 'Admin pulse error', err); }
  });

  app.get('/admin/notifications.json', requireAdmin, async (req, res) => {
    try {
      const read = Number(await getPref(req.admin, 'notifRead')) || 0;
      const rows = (await pool.query(`SELECT * FROM admin_notices ORDER BY id DESC LIMIT 80`)).rows.filter((n) => sees(req.admin, n.roles)).slice(0, 40);
      const icons = { report: 'flag', support: 'chat', backup: 'database', disk: 'drive', alert: 'alert' };
      res.json({ items: rows.map((n) => ({ id: Number(n.id), kind: n.kind, icon: icons[n.kind] || 'bell', title: n.title, body: n.body, link: n.link, at: n.at, unread: Number(n.id) > read })) });
    } catch (err) { serverError(res, 'Admin notifications error', err); }
  });

  app.post('/admin/notifications/read', requireAdmin, async (req, res) => {
    try {
      const max = (await pool.query('SELECT COALESCE(MAX(id), 0)::bigint AS m FROM admin_notices')).rows[0].m;
      await setPref(req.admin, 'notifRead', Number(max));
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Admin notifications error', err); }
  });

  app.get('/admin/search.json', requireAdmin, async (req, res) => {
    try {
      const q = String(req.query.q || '').trim().slice(0, 100);
      if (q.length < 2) return res.json({ results: [] });
      const like = `%${q.toLowerCase()}%`;
      const out = [];
      (await pool.query(`SELECT id, name, email, banned FROM users WHERE LOWER(email) LIKE $1 OR LOWER(name) LIKE $1 OR last_ip = $2 ORDER BY last_seen DESC NULLS LAST LIMIT 6`, [like, q])).rows
        .forEach((u) => out.push({ label: u.name, sub: u.email + (u.banned ? ' · blocked' : ''), href: `/admin/users/${u.id}`, icon: 'user', drawer: true }));
      if (security.canOpen(req.admin.role, '/admin/shares')) {
        (await pool.query(`SELECT s.code, s.sender_name, (SELECT name FROM share_files f WHERE f.share_id = s.id ORDER BY idx LIMIT 1) AS first
          FROM shares s WHERE s.code ILIKE $1 OR EXISTS (SELECT 1 FROM share_files f WHERE f.share_id = s.id AND LOWER(f.name) LIKE $2)
          ORDER BY s.created_at DESC LIMIT 6`, [q.replace(/[^0-9A-Za-z]/g, '') + '%', like])).rows
          .forEach((x) => out.push({ label: `Share ${x.code}`, sub: `${x.first || ''} · from ${x.sender_name}`, href: `/admin/shares/${x.code}`, icon: 'folder', drawer: true }));
      }
      if (/^[0-9a-f:.]{6,}$/i.test(q) && security.canOpen(req.admin.role, '/admin/log')) out.push({ label: `Everything from IP ${q}`, sub: 'Activity log', href: `/admin/log?q=${encodeURIComponent(q)}`, icon: 'globe' });
      res.json({ results: out });
    } catch (err) { serverError(res, 'Admin search error', err); }
  });

  require('./admin-more')(app, {
    pool, serverError, settings, plans, security, backups, UPLOAD_DIR, APP_DIR, requireAdmin, hidden, button, flash, backField, goBack,
    shareTable, SHARE_SELECT, findShare, deleteShare, computeAlerts, userTimeline
  });

  app.post('/admin/cleanup', requireAdmin, async (req, res) => {
    try {
      await cleanupNow();
      goBack(req, res, 'Expired files deleted.', '/admin/settings');
    } catch (err) { serverError(res, 'Admin cleanup error', err); }
  });
};

/** Small server-drawn bar chart (SVG) for the dashboard. */
function chart(title, rows, value, fmt) {
  const vals = rows.map(value);
  const max = Math.max(1, ...vals);
  const total = vals.reduce((a, b) => a + b, 0);
  const w = 300, h = 90, bw = w / vals.length;
  const bars = vals.map((v, i) => {
    const bh = Math.max(v > 0 ? 2 : 0, Math.round((v / max) * (h - 4)));
    const day = new Date(rows[i].day).toISOString().slice(5, 10);
    return `<rect x="${(i * bw + 1).toFixed(1)}" y="${h - bh}" width="${(bw - 2).toFixed(1)}" height="${bh}" rx="2"><title>${day}: ${fmt(v)}</title></rect>`;
  }).join('');
  return `<div class="card chart"><div class="k">${title}</div><div class="v">${fmt(total)}</div>
    <svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">${bars}</svg><div class="s">today: ${fmt(vals[vals.length - 1])} · best day: ${fmt(max === 1 && !vals.includes(1) ? 0 : max)}</div></div>`;
}

// Sidebar: grouped sections, filtered by what the signed-in admin's role may open.
const NAV = [
  ['Overview', [['dash', '/admin', 'Dashboard', 'grid'], ['live', '/admin/live', 'Live', 'activity'], ['alerts', '/admin/alerts', 'Alerts', 'alert', 'alerts']]],
  ['People', [['users', '/admin/users', 'Users', 'users'], ['support', '/admin/support', 'Support', 'chat', 'support'], ['messages', '/admin/messages', 'Messages', 'megaphone']]],
  ['Content', [['shares', '/admin/shares', 'Shares', 'folder'], ['reports', '/admin/reports', 'Reports', 'flag', 'reports'], ['banned', '/admin/banned', 'Banned files', 'ban'], ['storage', '/admin/storage', 'Storage', 'drive']]],
  ['Money', [['plans', '/admin/plans', 'Plans & codes', 'star'], ['sales', '/admin/sales', 'Sales', 'trending']]],
  ['System', [['admins', '/admin/admins', 'Admins', 'shield'], ['log', '/admin/log', 'Activity log', 'list'], ['security', '/admin/security', 'IP bans', 'globe'],
    ['backups', '/admin/backups', 'Backups', 'database'], ['app', '/admin/app', 'App update', 'phone'], ['crashes', '/admin/crashes', 'Crashes', 'bug'], ['settings', '/admin/settings', 'Settings', 'sliders']]]
];
const ACTIONS = [
  { label: 'Make a backup now', href: '/admin/backups', post: '/admin/backups/make', confirm: 'Make a database backup now?', icon: 'database', keys: 'backup save' },
  { label: 'Make plan codes', href: '/admin/plans', icon: 'ticket', keys: 'voucher code promo' },
  { label: 'Send a message to everyone', href: '/admin/messages', icon: 'megaphone', keys: 'broadcast notify' },
  { label: 'Ban an IP address', href: '/admin/security', icon: 'ban', keys: 'block ip' },
  { label: 'Add an admin', href: '/admin/admins', icon: 'shield', keys: 'team role' },
  { label: 'Upload a new app version', href: '/admin/app', icon: 'upload', keys: 'apk update' },
  { label: 'Change the announcement', href: '/admin/settings', icon: 'megaphone', keys: 'banner' },
  { label: 'Delete expired files now', href: '/admin/settings', post: '/admin/cleanup', confirm: 'Delete expired files now?', icon: 'wrench', keys: 'cleanup' },
  { label: 'Open reports', href: '/admin/reports', icon: 'flag', keys: 'moderation' },
  { label: 'Export users (CSV)', href: '/admin/users.csv', icon: 'download', keys: 'excel spreadsheet' }
];

function layout(title, active, body) {
  const me = current.getStore();
  const can = (href) => !me || !securityRef || securityRef.canOpen(me.role, href);
  let crumb = '';
  const nav = NAV.map(([group, items]) => {
    const ok = items.filter(([, href]) => can(href));
    if (!ok.length) return '';
    return `<div class="group">${group}</div>` + ok.map(([k, href, label, ic, badge]) => {
      if (k === active) crumb = group;
      return `<a href="${href}" class="${k === active ? 'on' : ''}" title="${label}">${ui.icon(ic)}<span class="label">${label}</span>${badge ? `<span class="badge" data-badge="${badge}"></span>` : ''}</a>`;
    }).join('');
  }).join('');
  const pages = NAV.flatMap(([group, items]) => items.filter(([, h]) => can(h)).map(([, href, label, ic]) => ({ label, sub: group, href, icon: ic })));
  const boot = me ? { csrf: csrfRef, role: me.role, user: me.username, pages, actions: ACTIONS.filter((a) => can(a.post || a.href)) } : { csrf: '', pages: [], actions: [] };
  const initial = esc((me ? me.username : '?').slice(0, 1).toUpperCase());
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0b0e16">
<title>${esc(title)} · Sendit admin</title>
<script>(function(){var t;try{t=JSON.parse(localStorage.getItem('adm-theme'))}catch(e){}document.documentElement.dataset.theme=t||(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark');try{if(JSON.parse(localStorage.getItem('adm-mini')))document.documentElement.classList.add('mini')}catch(e){}})()</script>
<link rel="stylesheet" href="/admin/assets/app.css?v=${ui.version}"></head><body>
${ui.sprite}
<aside class="side">
  <div class="brand"><span class="logo">${ui.icon('upload', 'sm')}</span><span>Sendit admin</span></div>
  <nav>${nav}</nav>
  ${me ? `<div class="pulse"><div><b data-live="online">–</b>online</div><div><b data-live="uploads">–</b>uploading</div><div><b data-live="downloads">–</b>downloading</div></div>` : ''}
  <button type="button" class="link collapse" data-act="mini" title="Collapse menu">${ui.icon('chevrons')}<span class="label">Collapse</span></button>
</aside>
<div class="scrim"></div>
<div class="shell">
  <header class="top">
    <button type="button" class="iconbtn menu-btn" data-act="menu" aria-label="Menu">${ui.icon('menu')}</button>
    <div class="crumb">${crumb ? `<small>${crumb} /</small>` : ''}${esc(title)}</div>
    <span class="grow"></span>
    ${me ? `<button type="button" class="searchbtn" data-act="palette" aria-label="Search">${ui.icon('search', 'sm')}<span>Search or jump to…</span><kbd>Ctrl K</kbd></button>
    <button type="button" class="iconbtn" data-act="bell" aria-label="Notifications">${ui.icon('bell')}<span class="badge" data-badge="notif"></span></button>
    <div class="notif"><div class="nh">Notifications</div><div class="nl"></div></div>` : ''}
    <button type="button" class="iconbtn" data-act="theme" aria-label="Light or dark">${ui.icon('sun')}</button>
    ${me ? `<button type="button" class="iconbtn" data-act="keys" aria-label="Keyboard shortcuts" title="Shortcuts (?)">${ui.icon('keyboard')}</button>
    <a class="userchip" href="/admin/me"><span class="avatar">${initial}</span><span>${esc(me.username)} <small>${esc(me.role)}</small></span></a>
    <form method="post" action="/admin/logout" class="inline"><input type="hidden" name="csrf" value="${csrfRef}"><button class="iconbtn" title="Sign out">${ui.icon('logout')}</button></form>` : ''}
  </header>
  <main>${body}</main>
</div>
<div class="drawer" role="dialog" aria-label="Details"><div class="dh"><b></b><a class="btn" href="#">${ui.icon('external', 'sm')} Full page</a>
  <button type="button" class="iconbtn" data-act="close-drawer" aria-label="Close">${ui.icon('x')}</button></div><div class="db"></div></div>
<div class="palette" role="dialog" aria-label="Search"><div class="pin">${ui.icon('search')}<input placeholder="Search people, shares, pages or actions…" aria-label="Search"><kbd>Esc</kbd></div>
  <div class="pl"></div><div class="pf"><span><kbd>↑</kbd> <kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span><span><kbd>Esc</kbd> close</span></div></div>
<div class="modal" role="dialog"></div>
<div class="lightbox"><button type="button" class="lb-btn lb-prev">‹</button><div class="lb-media"></div><button type="button" class="lb-btn lb-next">›</button>
  <button type="button" class="iconbtn lb-close">${ui.icon('x')}</button><div class="lb-cap"></div></div>
<div class="toasts"></div>
<script>window.ADMIN=${JSON.stringify(boot).replace(/</g, '\\u003c')}</script>
<script src="/admin/assets/app.js?v=${ui.version}"></script>
</body></html>`;
}

module.exports.helpers = { esc, when, bytes, left, layout, chart };
