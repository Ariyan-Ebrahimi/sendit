'use strict';

// Suspicious activity, worked out from the data each time the admin looks (nothing to store or clear).

/** Returns [{ level: 'high'|'medium', userId, name, email, what }]. */
module.exports = async function computeAlerts(pool, settings) {
  const sharesPerHour = Math.max(1, settings.int('alertSharesPerHour', 30));
  const gbPerDay = Math.max(0.1, Number(settings.get('alertGbPerDay')) || 5);
  const out = [];
  const add = (level, rows, what) => rows.forEach((r) => out.push({ level, userId: r.id, name: r.name, email: r.email, what: what(r) }));

  add('high', (await pool.query(`
    SELECT u.id, u.name, u.email, COUNT(*)::int AS n FROM shares s JOIN users u ON u.id = s.sender_user_id
    WHERE s.created_at > now() - interval '1 hour' GROUP BY u.id HAVING COUNT(*) >= $1 ORDER BY n DESC LIMIT 50`, [sharesPerHour])).rows,
  (r) => `made ${r.n} shares in the last hour`);

  add('medium', (await pool.query(`
    SELECT u.id, u.name, u.email, SUM(e.bytes)::bigint AS b FROM events e JOIN users u ON u.id = e.user_id
    WHERE e.kind = 'share' AND e.at > now() - interval '1 day' GROUP BY u.id HAVING SUM(e.bytes) >= $1 ORDER BY b DESC LIMIT 50`,
  [Math.round(gbPerDay * 1073741824)])).rows,
  (r) => `uploaded ${(Number(r.b) / 1073741824).toFixed(1)} GB in the last day`);

  add('high', (await pool.query(`
    SELECT u.id, u.name, u.email, COUNT(*)::int AS n FROM events e JOIN users u ON u.id = e.user_id
    WHERE e.kind = 'login_fail' AND e.at > now() - interval '1 hour' GROUP BY u.id HAVING COUNT(*) >= 10 ORDER BY n DESC LIMIT 50`)).rows,
  (r) => `${r.n} wrong passwords in the last hour (someone may be guessing)`);

  add('high', (await pool.query(`
    SELECT u.id, u.name, u.email, COUNT(*)::int AS n FROM reports rp JOIN shares s ON s.id = rp.share_id JOIN users u ON u.id = s.sender_user_id
    WHERE rp.status = 'open' GROUP BY u.id HAVING COUNT(*) >= 2 ORDER BY n DESC LIMIT 50`)).rows,
  (r) => `${r.n} open reports about their files`);

  add('medium', (await pool.query(`
    SELECT u.id, u.name, u.email, SUM(e.bytes)::bigint AS b FROM users u JOIN events e ON e.user_id = u.id AND e.kind = 'share'
    WHERE u.created_at > now() - interval '1 day' GROUP BY u.id HAVING SUM(e.bytes) >= 1073741824 LIMIT 50`)).rows,
  (r) => `new account (under a day old) already uploaded ${(Number(r.b) / 1073741824).toFixed(1)} GB`);

  const sameIp = (await pool.query(`
    SELECT signup_ip, COUNT(*)::int AS n, MIN(id) AS id, STRING_AGG(email, ', ' ORDER BY id) AS emails FROM users
    WHERE signup_ip IS NOT NULL AND created_at > now() - interval '1 day' GROUP BY signup_ip HAVING COUNT(*) >= 3`)).rows;
  sameIp.forEach((r) => out.push({ level: 'medium', userId: r.id, name: `${r.n} accounts`, email: r.emails, what: `${r.n} accounts made from one IP (${r.signup_ip}) today`, ip: r.signup_ip }));

  return out.sort((a, b) => (a.level === b.level ? 0 : a.level === 'high' ? -1 : 1));
};
