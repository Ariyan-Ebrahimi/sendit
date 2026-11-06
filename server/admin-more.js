'use strict';

// More admin pages: my account, admins & roles, activity log, IP bans, alerts, reports, banned files,
// storage, sales, backups and app updates. Access per role is decided in security.js.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');
const { esc, when, bytes, layout } = require('./admin').helpers;

const sha256Of = (full) => new Promise((resolve, reject) => {
  const h = crypto.createHash('sha256');
  fs.createReadStream(full).on('error', reject).on('data', (d) => h.update(d)).on('end', () => resolve(h.digest('hex')));
});

module.exports = function registerMoreAdmin(app, ctx) {
  const { pool, serverError, settings, security, backups, UPLOAD_DIR, APP_DIR, requireAdmin, hidden, button, flash, backField, goBack,
    shareTable, SHARE_SELECT, findShare, deleteShare, computeAlerts } = ctx;
  const back = (res, to, msg) => res.redirect(303, to + (to.includes('?') ? '&' : '?') + 'msg=' + encodeURIComponent(msg));
  const roleNote = { owner: 'everything', moderator: 'users (view/block), shares, reports, banned files, alerts, storage', support: 'users (view/message), support chat, messages' };

  // ---------------- my account ----------------

  app.get('/admin/me', requireAdmin, async (req, res) => {
    const me = req.admin;
    const sessions = (await pool.query('SELECT created_at, expires_at, ip, agent FROM admin_sessions WHERE admin_id = $1 AND expires_at > now() ORDER BY created_at DESC', [me.id])).rows;
    res.type('html').send(layout('My account', '', `
      ${flash(req)}
      <h1 class="title">👤 ${esc(me.username)} <span class="tag">${esc(me.role)}</span></h1>
      <p class="hint">You can use: ${esc(roleNote[me.role])}.</p>
      ${me.id === 0 ? '<div class="note">This is the main owner account. Its password is ADMIN_PASSWORD in server/.env.</div>' : `
      <h2>Change my password</h2>
      <form method="post" action="/admin/me/password" class="panel grid">${hidden}
        <label>Current password<input name="current" type="password" required></label>
        <label>New password (8+ characters)<input name="newPass" type="password" minlength="8" required></label>
        <div><button class="primary">Change</button></div></form>`}
      <h2>Where I'm signed in</h2>
      <table><tr><th>Since</th><th>Until</th><th>IP</th><th>Browser</th></tr>
      ${sessions.map((x) => `<tr><td><small>${when(x.created_at)}</small></td><td><small>${when(x.expires_at)}</small></td><td class="code">${esc(x.ip)}</td><td><small>${esc((x.agent || '').slice(0, 80))}</small></td></tr>`).join('')}</table>
      <div class="actions-row">${button('/admin/me/signout-all', 'Sign out everywhere', { cls: 'danger', confirm: 'Sign out of every browser (including this one)?' })}</div>`));
  });

  app.post('/admin/me/password', requireAdmin, async (req, res) => {
    try {
      if (!req.admin.id) return back(res, '/admin/me', 'The owner password is set in server/.env.');
      const ok = await security.checkLogin(req.admin.username, req.body.current);
      const np = String(req.body.newPass || '');
      if (!ok) return back(res, '/admin/me', 'Your current password is wrong.');
      if (np.length < 8) return back(res, '/admin/me', 'The new password needs at least 8 characters.');
      await pool.query('UPDATE admins SET password_hash = $1 WHERE id = $2', [await bcrypt.hash(np, 10), req.admin.id]);
      back(res, '/admin/me', 'Password changed.');
    } catch (err) { serverError(res, 'Admin password error', err); }
  });

  app.post('/admin/me/signout-all', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM admin_sessions WHERE admin_id = $1', [req.admin.id]);
      res.redirect(303, '/admin/login');
    } catch (err) { serverError(res, 'Admin sign-out error', err); }
  });

  // ---------------- admins ----------------

  app.get('/admin/admins', requireAdmin, async (req, res) => {
    try {
      const list = (await pool.query('SELECT * FROM admins ORDER BY id')).rows;
      const roleSel = (cur) => `<select name="role">${security.ROLES.map((r) => `<option value="${r}" ${r === cur ? 'selected' : ''}>${r}</option>`).join('')}</select>`;
      res.type('html').send(layout('Admins', 'admins', `
        ${flash(req)}
        <h1 class="title">Admins</h1>
        <p class="hint"><b>owner</b>: ${roleNote.owner} · <b>moderator</b>: ${roleNote.moderator} · <b>support</b>: ${roleNote.support}.<br>
          The main account "admin" (password in .env) is always an owner.</p>
        <table><tr><th>Username</th><th>Role</th><th>Last sign-in</th><th></th></tr>
        <tr><td><b>admin</b></td><td>owner</td><td><small>—</small></td><td><small>main account</small></td></tr>
        ${list.map((a) => `<tr><td><b>${esc(a.username)}</b>${a.disabled ? ' <span class="tag red">disabled</span>' : ''}</td>
          <td><form method="post" action="/admin/admins/${a.id}/role" class="inline">${hidden}${roleSel(a.role)}<button>Save</button></form></td>
          <td><small>${a.last_login ? when(a.last_login) : 'never'}</small></td>
          <td>${button(`/admin/admins/${a.id}/password`, 'New password', { confirm: `Make a new password for ${a.username}?` })}
            ${button(`/admin/admins/${a.id}/toggle`, a.disabled ? 'Enable' : 'Disable', { cls: a.disabled ? '' : 'danger' })}
            ${button(`/admin/admins/${a.id}/delete`, 'Delete', { cls: 'danger', confirm: `Delete admin ${a.username}?` })}</td></tr>`).join('')}
        </table>
        <h2>Add an admin</h2>
        <form method="post" action="/admin/admins" class="panel grid">${hidden}
          <label>Username<input name="username" required maxlength="40" pattern="[a-zA-Z0-9_.-]{3,40}" placeholder="e.g. sara"></label>
          <label>Password (8+ characters)<input name="newPass" type="password" minlength="8" required></label>
          <label>Role${roleSel('support')}</label>
          <div><button class="primary">Add admin</button></div></form>`));
    } catch (err) { serverError(res, 'Admins page error', err); }
  });

  app.post('/admin/admins', requireAdmin, async (req, res) => {
    try {
      const username = String(req.body.username || '').trim().toLowerCase();
      const pass = String(req.body.newPass || '');
      const role = security.ROLES.includes(req.body.role) ? req.body.role : 'support';
      if (!/^[a-z0-9_.-]{3,40}$/.test(username) || username === 'admin') return back(res, '/admin/admins', 'Pick another username (3–40 letters/numbers).');
      if (pass.length < 8) return back(res, '/admin/admins', 'The password needs at least 8 characters.');
      await pool.query('INSERT INTO admins (username, password_hash, role) VALUES ($1, $2, $3)', [username, await bcrypt.hash(pass, 10), role]);
      back(res, '/admin/admins', `Admin ${username} added. They sign in at /admin/login.`);
    } catch (err) {
      if (err.code === '23505') return back(res, '/admin/admins', 'That username is taken.');
      serverError(res, 'Add admin error', err);
    }
  });

  app.post('/admin/admins/:id/role', requireAdmin, async (req, res) => {
    try {
      const role = security.ROLES.includes(req.body.role) ? req.body.role : 'support';
      await pool.query('UPDATE admins SET role = $1 WHERE id = $2', [role, parseInt(req.params.id, 10)]);
      back(res, '/admin/admins', 'Role changed.');
    } catch (err) { serverError(res, 'Admin role error', err); }
  });

  app.post('/admin/admins/:id/password', requireAdmin, async (req, res) => {
    try {
      const temp = crypto.randomBytes(9).toString('base64url');
      const r = await pool.query('UPDATE admins SET password_hash = $1 WHERE id = $2 RETURNING username', [await bcrypt.hash(temp, 10), parseInt(req.params.id, 10)]);
      await pool.query('DELETE FROM admin_sessions WHERE admin_id = $1', [parseInt(req.params.id, 10)]);
      back(res, '/admin/admins', r.rows[0] ? `New password for ${r.rows[0].username}: ${temp}` : 'Not found.');
    } catch (err) { serverError(res, 'Admin new password error', err); }
  });

  app.post('/admin/admins/:id/toggle', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      await pool.query('UPDATE admins SET disabled = NOT disabled WHERE id = $1', [id]);
      await pool.query('DELETE FROM admin_sessions WHERE admin_id = $1', [id]);
      back(res, '/admin/admins', 'Saved.');
    } catch (err) { serverError(res, 'Admin toggle error', err); }
  });

  app.post('/admin/admins/:id/delete', requireAdmin, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      await pool.query('DELETE FROM admin_sessions WHERE admin_id = $1', [id]);
      await pool.query('DELETE FROM admins WHERE id = $1', [id]);
      back(res, '/admin/admins', 'Admin deleted.');
    } catch (err) { serverError(res, 'Admin delete error', err); }
  });

  // ---------------- activity log ----------------

  app.get('/admin/log', requireAdmin, async (req, res) => {
    try {
      const q = String(req.query.q || '').trim();
      const who = String(req.query.who || '');
      const rows = (await pool.query(`SELECT * FROM admin_log WHERE ($1 = '' OR action ILIKE $2 OR detail ILIKE $2 OR ip = $1) AND ($3 = '' OR admin_name = $3)
        ORDER BY at DESC LIMIT 500`, [q, `%${q}%`, who])).rows;
      const names = (await pool.query('SELECT DISTINCT admin_name FROM admin_log ORDER BY 1')).rows.map((r) => r.admin_name);
      const pretty = (a) => esc(a).replace(/^POST /, '').replace(/^GET /, 'viewed ');
      res.type('html').send(layout('Activity log', 'log', `
        <h1 class="title">Activity log</h1>
        <form class="search"><input name="q" value="${esc(q)}" placeholder="Search actions, details or an IP">
          <select name="who"><option value="">All admins</option>${names.map((n) => `<option ${n === who ? 'selected' : ''}>${esc(n)}</option>`).join('')}</select><button>Filter</button></form>
        <table><tr><th>When</th><th>Who</th><th>What</th><th>IP</th></tr>
        ${rows.map((r) => `<tr><td><small>${when(r.at)}</small></td><td>${esc(r.admin_name)} <small>${esc(r.role || '')}</small></td>
          <td>${pretty(r.action)}${r.detail ? `<br><small>${esc(r.detail)}</small>` : ''}</td><td class="code"><small>${esc(r.ip || '')}</small></td></tr>`).join('')
          || '<tr><td colspan="4"><small>Nothing yet.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin log error', err); }
  });

  // ---------------- IP bans ----------------

  app.get('/admin/security', requireAdmin, async (req, res) => {
    try {
      const bans = (await pool.query('SELECT * FROM ip_bans ORDER BY at DESC')).rows;
      const busy = (await pool.query(`SELECT signup_ip AS ip, COUNT(*)::int AS n FROM users WHERE signup_ip IS NOT NULL
        GROUP BY 1 HAVING COUNT(*) > 1 ORDER BY n DESC LIMIT 15`)).rows;
      res.type('html').send(layout('IP bans', 'security', `
        ${flash(req)}
        <h1 class="title">IP bans</h1>
        <p class="hint">Requests from these addresses get "blocked" everywhere except this admin panel. Your IP now: <span class="code">${esc(security.normIp(req.ip))}</span></p>
        <form method="post" action="/admin/security/ban" class="panel search">${hidden}
          <input name="ip" placeholder="IP address, e.g. 203.0.113.10" required><input name="reason" placeholder="Why (optional)"><button class="danger">Ban</button></form>
        <table><tr><th>IP</th><th>Why</th><th>By</th><th>When</th><th></th></tr>
        ${bans.map((b) => `<tr><td class="code">${esc(b.ip)}</td><td>${esc(b.reason || '')}</td><td>${esc(b.banned_by || '')}</td><td><small>${when(b.at)}</small></td>
          <td>${button('/admin/security/unban', 'Unban', { fields: `<input type="hidden" name="ip" value="${esc(b.ip)}">` })}</td></tr>`).join('')
          || '<tr><td colspan="5"><small>No banned IPs.</small></td></tr>'}</table>
        <h2>IPs with several accounts</h2>
        <table><tr><th>IP</th><th>Accounts made</th><th></th></tr>
        ${busy.map((b) => `<tr><td class="code">${esc(b.ip)}</td><td><a href="/admin/users?q=">${b.n}</a></td>
          <td>${button('/admin/security/ban', 'Ban', { cls: 'danger', confirm: `Ban ${b.ip}?`, fields: `<input type="hidden" name="ip" value="${esc(b.ip)}"><input type="hidden" name="reason" value="many accounts">` })}</td></tr>`).join('')
          || '<tr><td colspan="3"><small>None.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin security error', err); }
  });

  app.post('/admin/security/ban', requireAdmin, async (req, res) => {
    try {
      const ip = security.normIp(req.body.ip);
      if (!/^[0-9a-f:.]{3,64}$/i.test(ip)) return goBack(req, res, 'That is not an IP address.', '/admin/security');
      if (ip === security.normIp(req.ip)) return goBack(req, res, 'That is your own IP. Not banned.', '/admin/security');
      await security.banIp(ip, req.body.reason, req.admin.username);
      goBack(req, res, `${ip} is banned.`, '/admin/security');
    } catch (err) { serverError(res, 'Ban IP error', err); }
  });

  app.post('/admin/security/unban', requireAdmin, async (req, res) => {
    try {
      await security.unbanIp(req.body.ip);
      goBack(req, res, 'Unbanned.', '/admin/security');
    } catch (err) { serverError(res, 'Unban IP error', err); }
  });

  // ---------------- alerts ----------------

  app.get('/admin/alerts', requireAdmin, async (req, res) => {
    try {
      const list = await computeAlerts(pool, settings);
      res.type('html').send(layout('Alerts', 'alerts', `
        ${flash(req)}
        <h1 class="title">⚠ Suspicious activity</h1>
        <p class="hint">Worked out live from the last hour/day. Limits: ${settings.int('alertSharesPerHour', 30)} shares per hour, ${esc(settings.get('alertGbPerDay'))} GB per day.</p>
        <table><tr><th></th><th>Who</th><th>What</th><th></th></tr>
        ${list.map((a) => `<tr><td class="lvl-${a.level}">${a.level === 'high' ? '● high' : '● medium'}</td>
          <td><a href="/admin/users/${a.userId}">${esc(a.name)}</a><br><small>${esc(a.email)}</small></td><td>${esc(a.what)}</td>
          <td>${a.ip ? button('/admin/security/ban', 'Ban IP', { cls: 'danger', confirm: `Ban ${a.ip}?`, fields: `<input type="hidden" name="ip" value="${esc(a.ip)}"><input type="hidden" name="reason" value="alert">` + backField('/admin/alerts') })
            : `<a class="btn" href="/admin/users/${a.userId}">Open</a>`}</td></tr>`).join('') || '<tr><td colspan="4"><small>✓ Nothing suspicious right now.</small></td></tr>'}</table>
        <h2>Alert limits</h2>
        <form method="post" action="/admin/alerts/limits" class="panel grid">${hidden}
          <label>Shares per hour<input name="shares" type="number" min="1" value="${settings.int('alertSharesPerHour', 30)}"></label>
          <label>GB uploaded per day<input name="gb" type="number" min="0.1" step="0.1" value="${esc(settings.get('alertGbPerDay'))}"></label>
          <div><button class="primary">Save</button></div></form>`));
    } catch (err) { serverError(res, 'Admin alerts error', err); }
  });

  app.post('/admin/alerts/limits', requireAdmin, async (req, res) => {
    try {
      if (req.admin.role !== 'owner') return back(res, '/admin/alerts', 'Only the owner can change limits.');
      await settings.set('alertSharesPerHour', String(Math.max(1, parseInt(req.body.shares, 10) || 30)));
      await settings.set('alertGbPerDay', String(Math.max(0.1, Number(req.body.gb) || 5)));
      back(res, '/admin/alerts', 'Saved.');
    } catch (err) { serverError(res, 'Alert limits error', err); }
  });

  // ---------------- reports ----------------

  app.get('/admin/reports', requireAdmin, async (req, res) => {
    try {
      const status = ['open', 'done', 'all'].includes(req.query.status) ? req.query.status : 'open';
      const rows = (await pool.query(`
        SELECT r.code, MAX(r.created_at) AS last, COUNT(*)::int AS n, ARRAY_AGG(DISTINCT r.reason) AS reasons,
               (ARRAY_AGG(r.details ORDER BY r.created_at DESC) FILTER (WHERE r.details IS NOT NULL))[1] AS details,
               BOOL_OR(r.status = 'open') AS open, s.id AS share_id, s.blocked, s.sender_name, u.id AS uid, u.email,
               (SELECT COUNT(*)::int FROM share_files f WHERE f.share_id = s.id) AS files
        FROM reports r LEFT JOIN shares s ON s.id = r.share_id LEFT JOIN users u ON u.id = s.sender_user_id
        WHERE ($1 = 'all' OR ($1 = 'open') = (r.status = 'open'))
        GROUP BY r.code, s.id, u.id ORDER BY last DESC LIMIT 200`, [status])).rows;
      const act = (code, path, label, opts = {}) => button(`/admin/reports/share/${code}/${path}`, label, { ...opts, fields: backField('/admin/reports') });
      res.type('html').send(layout('Reports', 'reports', `
        ${flash(req)}
        <h1 class="title">🚩 Reports <small class="hint">${['open', 'done', 'all'].map((x) => x === status ? `<b>${x}</b>` : `<a href="/admin/reports?status=${x}">${x}</a>`).join(' · ')}</small></h1>
        <p class="hint">People can report a share from the app or the download page. Look at the files, then decide.</p>
        <table><tr><th>Share</th><th>Reports</th><th>Sender</th><th>Do</th></tr>
        ${rows.map((r) => `<tr><td class="code">${r.share_id ? `<a href="/admin/shares/${esc(r.code)}">${esc(r.code)}</a>` : esc(r.code) + ' <small>(gone)</small>'}
            ${r.blocked ? ' <span class="tag red">blocked</span>' : ''}<br><small>${r.files || 0} file(s)</small></td>
          <td>${r.n}× <b>${esc(r.reasons.join(', '))}</b>${r.details ? `<br><small>“${esc(r.details)}”</small>` : ''}<br><small>${when(r.last)}</small></td>
          <td>${r.uid ? `<a href="/admin/users/${r.uid}">${esc(r.sender_name)}</a><br><small>${esc(r.email)}</small>` : esc(r.sender_name || '')}</td>
          <td>${r.share_id ? `${r.blocked ? act(r.code, 'unblock', 'Unblock') : act(r.code, 'block', '⛔ Block', { cls: 'danger' })}
            ${act(r.code, 'delete', 'Delete share', { cls: 'danger', confirm: 'Delete this share and its files?' })}
            ${r.uid ? act(r.code, 'block-sender', 'Block sender', { cls: 'danger', confirm: `Block ${r.email}? They can't sign in or send.` }) : ''}` : ''}
            ${r.open ? act(r.code, 'ignore', 'Nothing wrong') : ''}</td></tr>`).join('') || '<tr><td colspan="4"><small>✓ No reports.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin reports error', err); }
  });

  app.post('/admin/reports/share/:code/:action', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.params.code);
      const action = req.params.action;
      const close = (status) => pool.query(`UPDATE reports SET status = $2, handled_by = $3, handled_at = now() WHERE code = $1 AND status = 'open'`,
        [req.params.code.toUpperCase(), status, req.admin.username]);
      let msg = 'Done.';
      if (action === 'ignore') { await close('ignored'); msg = 'Marked as fine.'; }
      else if (!s) { await close('gone'); msg = 'That share no longer exists.'; }
      else if (action === 'block') { await pool.query(`UPDATE shares SET blocked = TRUE, blocked_reason = $2 WHERE id = $1`, [s.id, `Blocked by ${req.admin.username}`]); await close('blocked'); msg = 'Share blocked.'; }
      else if (action === 'unblock') { await pool.query('UPDATE shares SET blocked = FALSE, blocked_reason = NULL WHERE id = $1', [s.id]); msg = 'Share unblocked.'; }
      else if (action === 'delete') { await close('deleted'); await deleteShare(s.id); msg = 'Share deleted.'; }
      else if (action === 'block-sender' && s.sender_user_id) {
        await pool.query('UPDATE users SET banned = TRUE WHERE id = $1', [s.sender_user_id]);
        await pool.query(`UPDATE shares SET blocked = TRUE, blocked_reason = 'Sender blocked' WHERE sender_user_id = $1`, [s.sender_user_id]);
        await close('blocked');
        msg = 'Sender blocked and all their shares hidden.';
      }
      goBack(req, res, msg, '/admin/reports');
    } catch (err) { serverError(res, 'Admin report action error', err); }
  });

  // ---------------- banned files ----------------

  app.get('/admin/banned', requireAdmin, async (req, res) => {
    try {
      const rows = (await pool.query(`SELECT b.*, (SELECT COUNT(*)::int FROM share_files f WHERE f.sha256 = b.sha256) AS copies FROM banned_hashes b ORDER BY at DESC`)).rows;
      res.type('html').send(layout('Banned files', 'banned', `
        ${flash(req)}
        <h1 class="title">🚫 Banned files</h1>
        <p class="hint">Each one is an exact file (by its SHA-256 fingerprint). When someone uploads the same file again, that share is blocked automatically.
          Ban a file from its share page. (Encrypted shares can't be checked.)</p>
        <table><tr><th>File</th><th>Why</th><th>By</th><th>Copies seen</th><th></th></tr>
        ${rows.map((b) => `<tr><td>${esc(b.name || '')}<br><small class="code">${esc(b.sha256.slice(0, 16))}…</small></td><td>${esc(b.reason || '')}</td>
          <td>${esc(b.banned_by || '')}<br><small>${when(b.at)}</small></td><td>${b.copies}</td>
          <td>${button('/admin/banned/remove', 'Unban', { fields: `<input type="hidden" name="sha256" value="${esc(b.sha256)}">` })}</td></tr>`).join('')
          || '<tr><td colspan="5"><small>No banned files.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin banned error', err); }
  });

  app.post('/admin/banned/add', requireAdmin, async (req, res) => {
    try {
      const s = await findShare(req.body.code);
      if (!s) return goBack(req, res, 'Share not found.', '/admin/banned');
      const f = (await pool.query('SELECT * FROM share_files WHERE share_id = $1 AND idx = $2', [s.id, parseInt(req.body.idx, 10)])).rows[0];
      if (!f || !f.storage_path) return goBack(req, res, 'File not found.', '/admin/banned');
      const hash = f.sha256 || await sha256Of(path.join(UPLOAD_DIR, path.basename(f.storage_path)));
      await pool.query('UPDATE share_files SET sha256 = $1 WHERE id = $2', [hash, f.id]);
      await pool.query(`INSERT INTO banned_hashes (sha256, name, reason, banned_by) VALUES ($1, $2, $3, $4) ON CONFLICT (sha256) DO NOTHING`,
        [hash, f.name.slice(0, 255), String(req.body.reason || 'Banned from share ' + s.code).slice(0, 200), req.admin.username]);
      const blocked = await pool.query(`UPDATE shares SET blocked = TRUE, blocked_reason = $2 WHERE id IN (SELECT share_id FROM share_files WHERE sha256 = $1)`,
        [hash, `Banned file: ${f.name}`.slice(0, 200)]);
      goBack(req, res, `File banned. ${blocked.rowCount} share(s) with it are blocked.`, '/admin/banned');
    } catch (err) { serverError(res, 'Ban file error', err); }
  });

  app.post('/admin/banned/remove', requireAdmin, async (req, res) => {
    try {
      await pool.query('DELETE FROM banned_hashes WHERE sha256 = $1', [String(req.body.sha256 || '')]);
      back(res, '/admin/banned', 'Unbanned. (Shares already blocked stay blocked; unblock them on their page.)');
    } catch (err) { serverError(res, 'Unban file error', err); }
  });

  // ---------------- storage ----------------

  function diskLeftovers(known) {
    let n = 0, size = 0;
    const names = [];
    for (const name of fs.readdirSync(UPLOAD_DIR)) {
      if (known.has(name)) continue;
      try { const st = fs.statSync(path.join(UPLOAD_DIR, name)); if (!st.isFile()) continue; n++; size += st.size; names.push(name); } catch { /* gone */ }
    }
    return { n, size, names };
  }

  app.get('/admin/storage', requireAdmin, async (req, res) => {
    try {
      let disk = null;
      try { const st = fs.statfsSync(UPLOAD_DIR); disk = { total: st.blocks * st.bsize, free: st.bavail * st.bsize }; } catch { /* old Node */ }
      const types = (await pool.query(`
        SELECT CASE WHEN f.mime LIKE 'image/%' THEN 'Photos' WHEN f.mime LIKE 'video/%' THEN 'Videos' WHEN f.mime LIKE 'audio/%' THEN 'Music & audio'
                    WHEN s.e2e THEN 'Encrypted' WHEN f.mime = 'application/pdf' OR f.mime LIKE 'text/%' OR f.mime LIKE '%document%' OR f.mime LIKE '%sheet%' THEN 'Documents'
                    WHEN f.mime LIKE '%zip%' OR f.mime LIKE '%rar%' OR f.mime LIKE '%7z%' THEN 'Archives'
                    WHEN f.mime LIKE '%android%' THEN 'Apps' ELSE 'Other' END AS kind,
               COUNT(*)::int AS n, COALESCE(SUM(f.size), 0)::bigint AS size
        FROM share_files f JOIN shares s ON s.id = f.share_id WHERE s.expires_at > now() GROUP BY 1 ORDER BY size DESC`)).rows;
      const total = types.reduce((a, t) => a + Number(t.size), 0) || 1;
      const big = (await pool.query(`SELECT f.name, f.size, s.code, s.sender_name, s.expires_at FROM share_files f JOIN shares s ON s.id = f.share_id
        WHERE s.expires_at > now() ORDER BY f.size DESC LIMIT 20`)).rows;
      const users = (await pool.query(`SELECT u.id, u.name, u.email, COALESCE(SUM(f.size), 0)::bigint AS used, COUNT(DISTINCT s.id)::int AS shares
        FROM users u JOIN shares s ON s.sender_user_id = u.id AND s.expires_at > now() JOIN share_files f ON f.share_id = s.id
        GROUP BY u.id ORDER BY used DESC LIMIT 20`)).rows;
      const old = (await pool.query(`SELECT COUNT(DISTINCT s.id)::int AS n, COALESCE(SUM(f.size), 0)::bigint AS size FROM shares s JOIN share_files f ON f.share_id = s.id
        WHERE s.expires_at > now() AND s.created_at < now() - interval '7 days'`)).rows[0];
      const known = new Set((await pool.query('SELECT storage_path FROM share_files WHERE storage_path IS NOT NULL UNION SELECT storage_path FROM transfers')).rows.map((r) => r.storage_path));
      const left = diskLeftovers(known);
      res.type('html').send(layout('Storage', 'storage', `
        ${flash(req)}
        <h1 class="title">Storage</h1>
        <div class="cards">
          <div class="card"><div class="k">Disk free</div><div class="v">${disk ? bytes(disk.free) : '—'}</div><div class="s">${disk ? 'of ' + bytes(disk.total) : ''}</div></div>
          <div class="card"><div class="k">Live files</div><div class="v">${bytes(total === 1 ? 0 : total)}</div><div class="s">${types.reduce((a, t) => a + t.n, 0)} files</div></div>
          <div class="card"><div class="k">Older than 7 days</div><div class="v">${bytes(old.size)}</div><div class="s">${old.n} share(s) still live</div></div>
          <div class="card"><div class="k">Leftovers on disk</div><div class="v">${bytes(left.size)}</div><div class="s">${left.n} file(s) no share uses</div>
            ${left.n ? button('/admin/storage/clean', 'Delete leftovers', { cls: 'danger', confirm: 'Delete files on disk that no share uses?' }) : ''}</div>
        </div>
        <h2>By type</h2>
        <div class="panel">${types.map((t) => `<div style="margin:8px 0"><b>${esc(t.kind)}</b> <small>${bytes(t.size)} · ${t.n} file(s)</small>
          <div class="bar"><i style="width:${Math.round(Number(t.size) / total * 100)}%"></i></div></div>`).join('') || '<small>No files.</small>'}</div>
        <div class="two">
          <div><h2>Biggest users</h2><table><tr><th>User</th><th>Used</th></tr>
            ${users.map((u) => `<tr><td><a href="/admin/users/${u.id}">${esc(u.name)}</a><br><small>${esc(u.email)}</small></td><td>${bytes(u.used)}<br><small>${u.shares} share(s)</small></td></tr>`).join('') || '<tr><td colspan="2"><small>None.</small></td></tr>'}</table></div>
          <div><h2>Biggest files</h2><table><tr><th>File</th><th>Size</th><th>Share</th></tr>
            ${big.map((f) => `<tr><td>${esc(f.name)}<br><small>${esc(f.sender_name)}</small></td><td>${bytes(f.size)}</td><td class="code"><a href="/admin/shares/${f.code}">${f.code}</a></td></tr>`).join('') || '<tr><td colspan="3"><small>None.</small></td></tr>'}</table></div>
        </div>`));
    } catch (err) { serverError(res, 'Admin storage error', err); }
  });

  app.post('/admin/storage/clean', requireAdmin, async (req, res) => {
    try {
      const known = new Set((await pool.query('SELECT storage_path FROM share_files WHERE storage_path IS NOT NULL UNION SELECT storage_path FROM transfers')).rows.map((r) => r.storage_path));
      const left = diskLeftovers(known);
      // Skip anything touched in the last 10 minutes: it may be an upload that just started.
      let n = 0;
      for (const name of left.names) {
        const full = path.join(UPLOAD_DIR, name);
        try { if (Date.now() - fs.statSync(full).mtimeMs > 10 * 60 * 1000) { fs.unlinkSync(full); n++; } } catch { /* gone */ }
      }
      back(res, '/admin/storage', `${n} leftover file(s) deleted.`);
    } catch (err) { serverError(res, 'Storage clean error', err); }
  });

  // ---------------- sales ----------------

  app.get('/admin/sales', requireAdmin, async (req, res) => {
    try {
      const months = (await pool.query(`
        SELECT to_char(m, 'YYYY-MM') AS month,
               (SELECT COUNT(*) FROM voucher_uses vu WHERE date_trunc('month', vu.at) = m)::int AS used,
               (SELECT COALESCE(SUM(v.value), 0) FROM voucher_uses vu JOIN vouchers v ON v.code = vu.code WHERE date_trunc('month', vu.at) = m)::bigint AS money,
               (SELECT COUNT(*) FROM vouchers v WHERE date_trunc('month', v.created_at) = m)::int AS made
        FROM generate_series(date_trunc('month', now()) - interval '11 months', date_trunc('month', now()), interval '1 month') m ORDER BY m`)).rows;
      const active = (await pool.query(`SELECT p.name, COUNT(u.id)::int AS n, p.price_amount FROM plans p
        LEFT JOIN users u ON u.plan_id = p.id AND u.plan_until > now() GROUP BY p.id ORDER BY p.sort`)).rows;
      const ending = (await pool.query(`SELECT u.id, u.name, u.email, u.plan_until, p.name AS plan FROM users u JOIN plans p ON p.id = u.plan_id
        WHERE u.plan_until > now() AND u.plan_until < now() + interval '7 days' ORDER BY u.plan_until LIMIT 100`)).rows;
      const thisMonth = months[months.length - 1];
      const maxMoney = Math.max(1, ...months.map((m) => Number(m.money)));
      const money = (n) => Number(n).toLocaleString('en-US');
      res.type('html').send(layout('Sales', 'sales', `
        <h1 class="title">Sales</h1>
        <p class="hint">Money is counted from each code's "money per use" (set when making codes; defaults to the plan's price number).</p>
        <div class="cards">
          <div class="card"><div class="k">This month</div><div class="v">${money(thisMonth.money)}</div><div class="s">${thisMonth.used} code(s) used</div></div>
          <div class="card"><div class="k">Last 12 months</div><div class="v">${money(months.reduce((a, m) => a + Number(m.money), 0))}</div><div class="s">${months.reduce((a, m) => a + m.used, 0)} code(s) used</div></div>
          <div class="card"><div class="k">Paying members now</div><div class="v">${active.reduce((a, p) => a + p.n, 0)}</div><div class="s">${active.map((p) => `${esc(p.name)} ${p.n}`).join(' · ')}</div></div>
          <div class="card"><div class="k">Ending in 7 days</div><div class="v">${ending.length}</div><div class="s">they get a reminder 3 days before</div></div>
        </div>
        <h2>Money per month</h2>
        <div class="panel"><svg viewBox="0 0 600 140" style="width:100%;height:160px">${months.map((m, i) => {
          const h = Math.round(Number(m.money) / maxMoney * 110);
          return `<rect x="${i * 50 + 6}" y="${120 - h}" width="38" height="${h}" rx="4" fill="#4F46E5"><title>${m.month}: ${money(m.money)} (${m.used} used, ${m.made} made)</title></rect>
            <text x="${i * 50 + 25}" y="136" fill="#918FA1" font-size="10" text-anchor="middle">${m.month.slice(2)}</text>`;
        }).join('')}</svg></div>
        <table><tr><th>Month</th><th>Codes made</th><th>Codes used</th><th>Money</th></tr>
          ${months.slice().reverse().map((m) => `<tr><td>${m.month}</td><td>${m.made}</td><td>${m.used}</td><td>${money(m.money)}</td></tr>`).join('')}</table>
        <h2>Plans ending soon</h2>
        <table><tr><th>User</th><th>Plan</th><th>Ends</th></tr>
          ${ending.map((u) => `<tr><td><a href="/admin/users/${u.id}">${esc(u.name)}</a><br><small>${esc(u.email)}</small></td><td>${esc(u.plan)}</td><td><small>${when(u.plan_until)}</small></td></tr>`).join('')
            || '<tr><td colspan="3"><small>None in the next 7 days.</small></td></tr>'}</table>`));
    } catch (err) { serverError(res, 'Admin sales error', err); }
  });

  // ---------------- backups ----------------

  app.get('/admin/backups', requireAdmin, (req, res) => {
    const list = backups.list();
    res.type('html').send(layout('Backups', 'backups', `
      ${flash(req)}
      <h1 class="title">Backups</h1>
      <p class="hint">A backup is a copy of the database (users, shares list, plans, codes, messages…). Uploaded files are not included.
        Saved in <span class="code">${esc(backups.DIR)}</span>.</p>
      <div class="actions-row">${button('/admin/backups/make', '💾 Make a backup now')}</div>
      <form method="post" action="/admin/backups/settings" class="panel search">${hidden}
        <label class="hint" style="align-self:center"><input type="checkbox" name="auto" value="1" ${settings.get('autoBackup') === 'true' ? 'checked' : ''}> Make one every day</label>
        <label class="hint" style="align-self:center">keep the newest <input name="keep" type="number" min="1" max="60" value="${settings.int('keepBackups', 7)}" style="width:70px"></label>
        <button>Save</button></form>
      <table><tr><th>Backup</th><th>Size</th><th>Made</th><th></th></tr>
      ${list.map((b) => `<tr><td class="code">${esc(b.name)}</td><td>${bytes(b.size)}</td><td><small>${when(b.at)}</small></td>
        <td><a class="btn" href="/admin/backups/${encodeURIComponent(b.name)}/download">⬇ Download</a>
          ${button(`/admin/backups/${encodeURIComponent(b.name)}/delete`, 'Delete', { cls: 'danger', confirm: 'Delete this backup?' })}</td></tr>`).join('')
        || '<tr><td colspan="4"><small>No backups yet.</small></td></tr>'}</table>
      <h2>Restoring</h2>
      <p class="hint">On the VPS: <span class="code">gunzip -c backups/NAME.sql.gz | psql "$DATABASE_URL"</span> (into an empty database). Ask before doing this on a live server.</p>`));
  });

  app.post('/admin/backups/make', requireAdmin, async (req, res) => {
    try {
      const b = await backups.make();
      backups.prune(settings.int('keepBackups', 7));
      back(res, '/admin/backups', `Backup made: ${b.name} (${bytes(b.size)}).`);
    } catch (err) { back(res, '/admin/backups', `Backup failed: ${err.message}`); }
  });

  app.post('/admin/backups/settings', requireAdmin, async (req, res) => {
    try {
      await settings.set('autoBackup', req.body.auto === '1' ? 'true' : 'false');
      await settings.set('keepBackups', String(Math.max(1, Math.min(60, parseInt(req.body.keep, 10) || 7))));
      back(res, '/admin/backups', 'Saved.');
    } catch (err) { serverError(res, 'Backup settings error', err); }
  });

  app.get('/admin/backups/:name/download', requireAdmin, (req, res) => {
    const full = backups.filePath(req.params.name);
    if (!full) return res.status(404).send('Not found.');
    res.download(full);
  });

  app.post('/admin/backups/:name/delete', requireAdmin, (req, res) => {
    try { backups.remove(req.params.name); back(res, '/admin/backups', 'Deleted.'); } catch (err) { serverError(res, 'Delete backup error', err); }
  });

  // ---------------- app updates ----------------

  const APK = path.join(APP_DIR, 'sendit.apk');

  app.get('/admin/app', requireAdmin, (req, res) => {
    const has = fs.existsSync(APK);
    const st = has ? fs.statSync(APK) : null;
    res.type('html').send(layout('App update', 'app', `
      ${flash(req)}
      <h1 class="title">App update</h1>
      <p class="hint">Version numbers are the app's <b>versionCode</b> (Sendit 1.8 = 7, 1.9 = 8…). People whose app is older than the minimum must update before they can use Sendit;
        older than the latest, they see a "new version" banner.</p>
      <form method="post" action="/admin/app" class="panel grid">${hidden}
        <label>Minimum version (0 = off)<input name="minVersion" type="number" min="0" value="${settings.int('minVersion')}"></label>
        <label>Latest version<input name="latestVersion" type="number" min="0" value="${settings.int('latestVersion')}"></label>
        <label class="wide">Download link (empty = the file uploaded below)<input name="updateUrl" value="${esc(settings.get('updateUrl') || '')}" placeholder="https://…"></label>
        <label class="wide">What's new (shown in the app)<textarea name="updateNote" rows="3" maxlength="500">${esc(settings.get('updateNote') || '')}</textarea></label>
        <div><button class="primary">Save</button></div></form>
      <h2>App file (APK)</h2>
      <div class="panel">
        ${has ? `<p>Uploaded: <b>${bytes(st.size)}</b> · ${when(st.mtime)} · public page: <a href="/download" target="_blank">/download</a></p>` : '<p class="hint">No file yet.</p>'}
        <input type="file" id="apk" accept=".apk"> <button class="primary" onclick="up()">Upload</button> <span id="st" class="hint"></span>
      </div>
      <script>
      async function up(){const f=document.getElementById('apk').files[0];const st=document.getElementById('st');if(!f){st.textContent='Pick the .apk file first.';return}
        st.textContent='Uploading…';try{const r=await fetch('/admin/app/apk',{method:'PUT',headers:{'X-CSRF':'${ctx.hidden.match(/value="([^"]+)"/)[1]}','Content-Type':'application/octet-stream'},body:f});
        st.textContent=r.ok?'✓ Uploaded. Reloading…':'Failed ('+r.status+')';if(r.ok)setTimeout(()=>location.reload(),800)}catch(e){st.textContent=e.message}}
      </script>`));
  });

  app.post('/admin/app', requireAdmin, async (req, res) => {
    try {
      const n = (v) => String(Math.max(0, parseInt(v, 10) || 0));
      await settings.set('minVersion', n(req.body.minVersion));
      await settings.set('latestVersion', n(req.body.latestVersion));
      const url = String(req.body.updateUrl || '').trim();
      if (url && !/^https?:\/\//i.test(url)) return back(res, '/admin/app', 'The link must start with http:// or https://');
      await settings.set('updateUrl', url.slice(0, 500));
      await settings.set('updateNote', String(req.body.updateNote || '').trim().slice(0, 500));
      back(res, '/admin/app', 'Saved. Apps check this when they open.');
    } catch (err) { serverError(res, 'App update settings error', err); }
  });

  app.put('/admin/app/apk', requireAdmin, (req, res) => {
    fs.mkdirSync(APP_DIR, { recursive: true });
    const tmp = APK + '.part';
    const out = fs.createWriteStream(tmp);
    let size = 0;
    const max = 300 * 1024 * 1024;
    req.on('data', (c) => { size += c.length; if (size > max) { req.destroy(); out.destroy(); fs.unlink(tmp, () => {}); } });
    req.pipe(out);
    out.on('finish', () => {
      if (size < 1000) { fs.unlink(tmp, () => {}); return res.status(400).json({ error: 'That file is too small to be an app.' }); }
      fs.renameSync(tmp, APK);
      res.json({ ok: true, size });
    });
    out.on('error', (e) => serverError(res, 'APK upload error', e));
  });
};
