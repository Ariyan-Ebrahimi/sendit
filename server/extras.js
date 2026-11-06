'use strict';

// Contacts (with "receive automatically"), crash reports from the app, and admin broadcasts.

module.exports = function registerExtras(app, deps) {
  const { pool, authenticateToken, optionalToken, rateLimit, serverError } = deps;
  const crashLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 30, message: 'Too many reports.' });
  const contactLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 60, message: 'Too many requests. Please wait a few minutes.' });

  // ---------- Contacts ----------

  app.get('/api/contacts', authenticateToken, async (req, res) => {
    try {
      const r = await pool.query(
        `SELECT u.id, u.name, u.email, c.auto_accept FROM contacts c JOIN users u ON u.id = c.contact_id
         WHERE c.owner_id = $1 ORDER BY LOWER(u.name)`,
        [req.user.id]
      );
      res.json({ contacts: r.rows.map((c) => ({ id: c.id, name: c.name, email: c.email, autoAccept: c.auto_accept })) });
    } catch (err) { serverError(res, 'Contacts error', err); }
  });

  app.post('/api/contacts', contactLimiter, authenticateToken, async (req, res) => {
    try {
      const email = String(req.body.email || '').trim().toLowerCase();
      const u = (await pool.query('SELECT id, name, email FROM users WHERE email = $1', [email])).rows[0];
      if (!u) return res.status(404).json({ error: 'No Sendit account uses that email.' });
      if (u.id === req.user.id) return res.status(400).json({ error: "That's your own email." });
      await pool.query(
        'INSERT INTO contacts (owner_id, contact_id) VALUES ($1, $2) ON CONFLICT (owner_id, contact_id) DO NOTHING',
        [req.user.id, u.id]
      );
      res.status(201).json({ id: u.id, name: u.name, email: u.email, autoAccept: false });
    } catch (err) { serverError(res, 'Add contact error', err); }
  });

  app.patch('/api/contacts/:id', authenticateToken, async (req, res) => {
    try {
      await pool.query('UPDATE contacts SET auto_accept = $1 WHERE owner_id = $2 AND contact_id = $3',
        [req.body.autoAccept === true, req.user.id, parseInt(req.params.id, 10)]);
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Update contact error', err); }
  });

  app.delete('/api/contacts/:id', authenticateToken, async (req, res) => {
    try {
      await pool.query('DELETE FROM contacts WHERE owner_id = $1 AND contact_id = $2', [req.user.id, parseInt(req.params.id, 10)]);
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Delete contact error', err); }
  });

  // ---------- Groups of contacts ----------

  async function groupsOf(userId) {
    const r = await pool.query(
      `SELECT g.id, g.name, COALESCE(json_agg(json_build_object('id', u.id, 'name', u.name, 'email', u.email)
                ORDER BY LOWER(u.name)) FILTER (WHERE u.id IS NOT NULL), '[]') AS members
       FROM contact_groups g LEFT JOIN contact_group_members m ON m.group_id = g.id LEFT JOIN users u ON u.id = m.user_id
       WHERE g.owner_id = $1 GROUP BY g.id ORDER BY LOWER(g.name)`,
      [userId]
    );
    return r.rows.map((g) => ({ id: g.id, name: g.name, members: g.members }));
  }

  // Members are given as emails; only people who are my contacts can be added.
  async function setMembers(ownerId, groupId, emails) {
    const clean = [...new Set((Array.isArray(emails) ? emails : []).map((e) => String(e).trim().toLowerCase()))].slice(0, 100);
    await pool.query('DELETE FROM contact_group_members WHERE group_id = $1', [groupId]);
    if (clean.length === 0) return;
    await pool.query(
      `INSERT INTO contact_group_members (group_id, user_id)
       SELECT $1, u.id FROM users u JOIN contacts c ON c.contact_id = u.id AND c.owner_id = $2
       WHERE u.email = ANY($3::text[]) ON CONFLICT DO NOTHING`,
      [groupId, ownerId, clean]
    );
  }

  app.get('/api/groups', authenticateToken, async (req, res) => {
    try { res.json({ groups: await groupsOf(req.user.id) }); } catch (err) { serverError(res, 'Groups error', err); }
  });

  app.post('/api/groups', contactLimiter, authenticateToken, async (req, res) => {
    try {
      const name = String(req.body.name || '').trim().slice(0, 80);
      if (!name) return res.status(400).json({ error: 'Give the group a name.' });
      const g = (await pool.query('INSERT INTO contact_groups (owner_id, name) VALUES ($1, $2) RETURNING id', [req.user.id, name])).rows[0];
      await setMembers(req.user.id, g.id, req.body.emails);
      res.status(201).json({ groups: await groupsOf(req.user.id), id: g.id });
    } catch (err) { serverError(res, 'Create group error', err); }
  });

  app.put('/api/groups/:id', authenticateToken, async (req, res) => {
    try {
      const id = parseInt(req.params.id, 10);
      const name = String(req.body.name || '').trim().slice(0, 80);
      const r = await pool.query('UPDATE contact_groups SET name = COALESCE(NULLIF($1, \'\'), name) WHERE id = $2 AND owner_id = $3 RETURNING id', [name, id, req.user.id]);
      if (!r.rows[0]) return res.status(404).json({ error: 'Group not found.' });
      if (Array.isArray(req.body.emails)) await setMembers(req.user.id, id, req.body.emails);
      res.json({ groups: await groupsOf(req.user.id) });
    } catch (err) { serverError(res, 'Update group error', err); }
  });

  app.delete('/api/groups/:id', authenticateToken, async (req, res) => {
    try {
      await pool.query('DELETE FROM contact_groups WHERE id = $1 AND owner_id = $2', [parseInt(req.params.id, 10), req.user.id]);
      res.json({ ok: true });
    } catch (err) { serverError(res, 'Delete group error', err); }
  });

  // ---------- Notices for me: "your files were downloaded", "expiring soon", support replies ----------

  app.get('/api/notices', authenticateToken, async (req, res) => {
    try {
      const after = parseInt(req.query.after, 10) || 0;
      const r = await pool.query(
        `SELECT id, kind, code, name, count, body, created_at FROM notices
         WHERE user_id = $1 AND id > $2 AND created_at > now() - interval '3 days' ORDER BY id LIMIT 20`,
        [req.user.id, after]
      );
      res.json({ notices: r.rows.map((n) => ({ id: Number(n.id), kind: n.kind, code: n.code, name: n.name, count: n.count, body: n.body, createdAt: n.created_at })) });
    } catch (err) { serverError(res, 'Notices error', err); }
  });

  // ---------- Support chat with the admin ----------

  app.get('/api/support', authenticateToken, async (req, res) => {
    try {
      const r = await pool.query(
        'SELECT id, from_admin, body, created_at FROM support_messages WHERE user_id = $1 ORDER BY id DESC LIMIT 100',
        [req.user.id]
      );
      res.json({ messages: r.rows.reverse().map((m) => ({ id: m.id, fromAdmin: m.from_admin, body: m.body, createdAt: m.created_at })) });
    } catch (err) { serverError(res, 'Support error', err); }
  });

  app.post('/api/support', contactLimiter, authenticateToken, async (req, res) => {
    try {
      const body = String(req.body.body || '').trim().slice(0, 2000);
      if (!body) return res.status(400).json({ error: 'Write a message first.' });
      await pool.query('INSERT INTO support_messages (user_id, from_admin, body) VALUES ($1, FALSE, $2)', [req.user.id, body]);
      // A new message reopens a solved chat and rings the admins' bell.
      await pool.query('UPDATE users SET support_solved = FALSE WHERE id = $1', [req.user.id]);
      if (deps.security) deps.security.notify('support', `${req.user.name || 'Someone'} wrote to support`, body.slice(0, 140), `/admin/support?id=${req.user.id}`, 'owner,support');
      res.status(201).json({ ok: true });
    } catch (err) { serverError(res, 'Support send error', err); }
  });

  // ---------- Crash reports ----------

  app.post('/api/crash', crashLimiter, optionalToken, async (req, res) => {
    try {
      const stack = String(req.body.stack || '').slice(0, 20000);
      if (!stack) return res.status(400).json({ error: 'Empty report.' });
      await pool.query(
        'INSERT INTO crash_reports (user_id, app_version, device, android, stack) VALUES ($1, $2, $3, $4, $5)',
        [req.user ? req.user.id : null, String(req.body.appVersion || '').slice(0, 40),
          String(req.body.device || '').slice(0, 120), String(req.body.android || '').slice(0, 40), stack]
      );
      // Keep the table small.
      await pool.query(`DELETE FROM crash_reports WHERE created_at < now() - interval '90 days'`);
      res.status(201).json({ ok: true });
    } catch (err) { serverError(res, 'Crash report error', err); }
  });

  // ---------- Broadcasts (admin → everyone) ----------

  app.get('/api/broadcasts', authenticateToken, async (req, res) => {
    try {
      const after = parseInt(req.query.after, 10) || 0;
      const r = await pool.query(
        `SELECT id, title, body, created_at FROM broadcasts WHERE id > $1 AND created_at > now() - interval '7 days'
         ORDER BY id DESC LIMIT 5`,
        [after]
      );
      res.json({ broadcasts: r.rows.map((b) => ({ id: b.id, title: b.title, body: b.body, createdAt: b.created_at })) });
    } catch (err) { serverError(res, 'Broadcasts error', err); }
  });
};
