'use strict';

const express = require('express');
const cors = require('cors');
const multer = require('multer');
const { Pool } = require('pg');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
require('dotenv').config({ path: path.join(__dirname, '.env') });

// ==================== CONFIG ====================

const PORT = parseInt(process.env.PORT, 10) || 4000;
const HOST = process.env.HOST || '0.0.0.0';
const JWT_SECRET = process.env.JWT_SECRET;
const DATABASE_URL = process.env.DATABASE_URL;
const MAX_FILE_SIZE_MB = parseInt(process.env.MAX_FILE_SIZE_MB, 10) || 1024;
const TRANSFER_TTL_HOURS = parseInt(process.env.TRANSFER_TTL_HOURS, 10) || 24;
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, 'uploads');
const DEFAULT_QUOTA_MB = parseInt(process.env.USER_QUOTA_MB, 10) || 2048;

if (!JWT_SECRET || JWT_SECRET.length < 24 || /change_me|super_secret/i.test(JWT_SECRET)) {
  console.error('FATAL: set a long random JWT_SECRET in server/.env (deploy/setup-vps.sh does this for you).');
  process.exit(1);
}
if (!DATABASE_URL) {
  console.error('FATAL: DATABASE_URL is missing in server/.env');
  process.exit(1);
}

fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const pool = new Pool({ connectionString: DATABASE_URL, max: 10 });
const settings = require('./settings')(pool, { quotaMb: DEFAULT_QUOTA_MB, maxFileMb: MAX_FILE_SIZE_MB, ttlHours: TRANSFER_TTL_HOURS });
pool.on('error', (err) => console.error('PostgreSQL pool error:', err.message));

const app = express();
// Only trust X-Forwarded-For behind a real proxy (set TRUST_PROXY=1 with Nginx/Caddy); otherwise IP bans could be dodged.
app.set('trust proxy', process.env.TRUST_PROXY === '1');
app.disable('x-powered-by');
app.use(cors());
app.use(express.json({ limit: '32kb' }));
app.use(express.urlencoded({ extended: false, limit: '32kb' }));

const security = require('./security')(pool);
const backups = require('./backups');
app.use(security.ipBanMiddleware);

// ==================== DATABASE ====================

async function initDb() {
  const sql = fs.readFileSync(path.join(__dirname, 'init.sql'), 'utf8');
  await pool.query(sql);
  await settings.load();
  await security.loadBans();
  console.log('PostgreSQL schema ready.');
}

// ==================== HELPERS ====================

function signToken(user) {
  return jwt.sign({ id: user.id, email: user.email, name: user.name }, JWT_SECRET, { expiresIn: '30d' });
}

function readToken(req) {
  const header = req.headers.authorization || '';
  return header.startsWith('Bearer ') ? header.slice(7) : null;
}

function authenticateToken(req, res, next) {
  const token = readToken(req);
  if (!token) return res.status(401).json({ error: 'Please sign in first.' });
  jwt.verify(token, JWT_SECRET, async (err, user) => {
    if (err) return res.status(401).json({ error: 'Your session expired. Please sign in again.' });
    try {
      const r = await pool.query('SELECT name, email, banned, last_seen FROM users WHERE id = $1', [user.id]);
      if (!r.rows[0]) return res.status(401).json({ error: 'Your session expired. Please sign in again.' });
      if (r.rows[0].banned) return res.status(403).json({ error: 'This account is blocked. Contact the Sendit admin.' });
      req.user = { ...user, name: r.rows[0].name, email: r.rows[0].email };
      // "Online now", last IP and app version for the admin (written at most every 2 minutes per user).
      const seen = r.rows[0].last_seen;
      if (!seen || Date.now() - new Date(seen) > 120000) {
        const version = String(req.headers['x-app-version'] || '').replace(/[^0-9.]/g, '').slice(0, 20) || null;
        pool.query('UPDATE users SET last_seen = now(), last_ip = $2, app_version = COALESCE($3, app_version) WHERE id = $1',
          [user.id, security.normIp(req.ip), version]).catch(() => {});
        // First time today: counts as an active user on the dashboard.
        if (!seen || new Date(seen).toDateString() !== new Date().toDateString()) {
          pool.query(`INSERT INTO events (kind, user_id) VALUES ('active', $1)`, [user.id]).catch(() => {});
        }
      }
      next();
    } catch (e) {
      serverError(res, 'Auth check error', e);
    }
  });
}

function optionalToken(req, res, next) {
  const token = readToken(req);
  if (!token) { req.user = null; return next(); }
  jwt.verify(token, JWT_SECRET, (err, user) => {
    req.user = err ? null : user;
    next();
  });
}

// Small in-memory rate limiter (no extra dependency). Good enough for one server process.
function rateLimit({ windowMs, max, message }) {
  const hits = new Map();
  setInterval(() => {
    const now = Date.now();
    for (const [key, entry] of hits) if (entry.reset <= now) hits.delete(key);
  }, windowMs).unref();
  return (req, res, next) => {
    const now = Date.now();
    const key = req.ip;
    let entry = hits.get(key);
    if (!entry || entry.reset <= now) {
      entry = { count: 0, reset: now + windowMs };
      hits.set(key, entry);
    }
    entry.count += 1;
    if (entry.count > max) {
      res.setHeader('Retry-After', Math.ceil((entry.reset - now) / 1000));
      return res.status(429).json({ error: message });
    }
    next();
  };
}

const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 30, message: 'Too many attempts. Please wait a few minutes and try again.' });
const uploadLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 120, message: 'Upload limit reached. Please try again later.' });
const lookupLimiter = rateLimit({ windowMs: 60 * 1000, max: 60, message: 'Too many code lookups. Please slow down.' });

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_CHARS = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';

function generateCode() {
  let code = '';
  for (let i = 0; i < 6; i++) code += CODE_CHARS[crypto.randomInt(CODE_CHARS.length)];
  return code;
}

function cleanCode(raw) {
  return String(raw || '').trim().toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 16);
}

function serverError(res, where, err) {
  console.error(`${where}:`, err);
  res.status(500).json({ error: 'Something went wrong on the server. Please try again.' });
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => cb(null, `${Date.now()}-${crypto.randomBytes(8).toString('hex')}`)
});
const upload = multer({ storage, limits: { fileSize: MAX_FILE_SIZE_MB * 1024 * 1024, files: 1 } });

// ==================== ROUTES ====================

app.get('/', (req, res) => res.type('text').send('Sendit relay server is running.'));

app.get('/api/health', async (req, res) => {
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok', name: 'Sendit Relay', db: 'ok', timestamp: Date.now() });
  } catch (err) {
    res.status(503).json({ status: 'degraded', name: 'Sendit Relay', db: 'down', timestamp: Date.now() });
  }
});

// Things the app shows to everyone (set on the admin Settings page).
// ==================== LIVE TRAFFIC (admin Live page) ====================
// Bytes in/out per minute for the last hour, plus transfers happening right now.
const traffic = { minutes: [], uploads: 0, downloads: 0 };
function trafficMinute() {
  const m = Math.floor(Date.now() / 60000);
  let cur = traffic.minutes[traffic.minutes.length - 1];
  if (!cur || cur.m !== m) {
    cur = { m, bytesIn: 0, bytesOut: 0, requests: 0 };
    traffic.minutes.push(cur);
    while (traffic.minutes.length > 60) traffic.minutes.shift();
  }
  return cur;
}
app.use((req, res, next) => {
  const isUpload = req.method === 'PUT' && /^\/api\/shares\/[^/]+\/files\//.test(req.path);
  const isDownload = req.method === 'GET' && /^\/s\/[^/]+\/(f\/|zip)/.test(req.path);
  if (isUpload) traffic.uploads++;
  if (isDownload) traffic.downloads++;
  let counted = false;
  const done = () => {
    if (counted) return;
    counted = true;
    if (isUpload) traffic.uploads--;
    if (isDownload) traffic.downloads--;
    const cur = trafficMinute();
    cur.requests++;
    cur.bytesIn += Number(req.headers['content-length'] || 0);
    cur.bytesOut += Number(res.getHeader('content-length') || 0);
  };
  res.on('finish', done);
  res.on('close', done);
  next();
});

const APP_DIR = path.join(__dirname, 'app-download');
const APK = path.join(APP_DIR, 'sendit.apk');
const updateUrl = (req) => settings.get('updateUrl') || (fs.existsSync(APK) ? `${req.protocol}://${req.get('host')}/download/sendit.apk` : '');

app.get('/api/app/config', (req, res) => {
  res.json({
    announcement: settings.announcement(), signupsOpen: settings.signupsOpen(), maxFileMb: settings.maxFileMb(),
    // App versions are Android versionCode numbers. Below minVersion the app asks the user to update first.
    minVersion: settings.int('minVersion'), latestVersion: settings.int('latestVersion'),
    updateUrl: updateUrl(req), updateNote: settings.get('updateNote') || ''
  });
});

// The newest app, for people who can't use Google Play.
app.get('/download/sendit.apk', (req, res) => {
  if (!fs.existsSync(APK)) return res.status(404).type('text').send('No app file has been uploaded yet.');
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader('Content-Disposition', 'attachment; filename="Sendit.apk"');
  fs.createReadStream(APK).pipe(res);
});
app.get(['/download', '/app'], (req, res) => {
  const has = fs.existsSync(APK);
  const size = has ? (fs.statSync(APK).size / 1048576).toFixed(1) + ' MB' : '';
  res.type('html').send(`<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Get Sendit</title>
    <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0F131D;color:#DFE2F1;font-family:system-ui,Tahoma,sans-serif;text-align:center}
    a{display:inline-block;margin-top:18px;padding:16px 28px;border-radius:16px;background:#4F46E5;color:#fff;text-decoration:none;font-size:18px}p{color:#918FA1}</style></head>
    <body><div><h1>Sendit</h1><p>Send files to anyone, anywhere.</p>
    ${has ? `<a href="/download/sendit.apk">⬇ Download for Android (${size})</a><p>Version ${settings.int('latestVersion') || ''}</p>` : '<p>The download is not ready yet.</p>'}
    </div></body></html>`);
});

app.post('/api/auth/signup', authLimiter, async (req, res) => {
  try {
    const name = String(req.body.name || '').trim();
    const email = String(req.body.email || '').trim().toLowerCase();
    const password = String(req.body.password || '');

    if (!settings.signupsOpen()) return res.status(403).json({ error: 'New sign-ups are closed right now.' });
    if (!name || !email || !password) return res.status(400).json({ error: 'Name, email and password are required.' });
    if (name.length > 60) return res.status(400).json({ error: 'Name is too long (max 60 characters).' });
    if (!EMAIL_RE.test(email) || email.length > 255) return res.status(400).json({ error: 'Please enter a valid email address.' });
    if (password.length < 6) return res.status(400).json({ error: 'Password must be at least 6 characters.' });
    if (password.length > 200) return res.status(400).json({ error: 'Password is too long.' });

    const passwordHash = await bcrypt.hash(password, 10);
    let result;
    try {
      result = await pool.query(
        'INSERT INTO users (name, email, password_hash) VALUES ($1, $2, $3) RETURNING id, name, email',
        [name, email, passwordHash]
      );
    } catch (err) {
      if (err.code === '23505') return res.status(409).json({ error: 'An account with this email already exists. Try signing in.' });
      throw err;
    }

    const user = result.rows[0];
    pool.query(`INSERT INTO events (kind, user_id) VALUES ('signup', $1)`, [user.id]).catch(() => {});
    await pool.query('UPDATE users SET signup_ip = $2, last_ip = $2 WHERE id = $1', [user.id, security.normIp(req.ip)]).catch(() => {});
    await plans.applyTrial(user.id).catch((e) => console.error('trial:', e.message));
    res.status(201).json({ token: signToken(user), user });
  } catch (err) {
    serverError(res, 'Signup error', err);
  }
});

app.post('/api/auth/login', authLimiter, async (req, res) => {
  try {
    const email = String(req.body.email || '').trim().toLowerCase();
    const password = String(req.body.password || '');
    if (!email || !password) return res.status(400).json({ error: 'Email and password are required.' });

    const result = await pool.query('SELECT id, name, email, password_hash, banned FROM users WHERE email = $1', [email]);
    const row = result.rows[0];
    const ok = row ? await bcrypt.compare(password, row.password_hash) : false;
    if (!ok) {
      if (row) pool.query(`INSERT INTO events (kind, user_id) VALUES ('login_fail', $1)`, [row.id]).catch(() => {});
      return res.status(401).json({ error: 'Wrong email or password.' });
    }
    if (row.banned) return res.status(403).json({ error: 'This account is blocked. Contact the Sendit admin.' });

    const user = { id: row.id, name: row.name, email: row.email };
    res.json({ token: signToken(user), user });
  } catch (err) {
    serverError(res, 'Login error', err);
  }
});

app.get('/api/auth/me', authenticateToken, async (req, res) => {
  try {
    const result = await pool.query('SELECT id, name, email, created_at FROM users WHERE id = $1', [req.user.id]);
    if (result.rows.length === 0) return res.status(404).json({ error: 'User not found.' });
    res.json(result.rows[0]);
  } catch (err) {
    serverError(res, 'Profile error', err);
  }
});

app.post('/api/transfers/upload', uploadLimiter, optionalToken, (req, res) => {
  upload.single('file')(req, res, async (uploadErr) => {
    if (uploadErr) {
      const msg = uploadErr.code === 'LIMIT_FILE_SIZE'
        ? `File is too large. The limit is ${MAX_FILE_SIZE_MB} MB.`
        : 'Upload failed. Please try again.';
      return res.status(uploadErr.code === 'LIMIT_FILE_SIZE' ? 413 : 400).json({ error: msg });
    }
    if (!req.file) return res.status(400).json({ error: 'No file provided.' });

    const senderName = String(req.body.senderName || (req.user && req.user.name) || 'Sendit user').trim().slice(0, 100);
    const senderUserId = req.user ? req.user.id : null;
    // Multer reads the multipart filename as latin1; the app sends UTF-8, so re-decode unless that breaks it.
    const utf8Name = Buffer.from(req.file.originalname, 'latin1').toString('utf8');
    const fileName = (utf8Name.includes('�') ? req.file.originalname : utf8Name).slice(0, 255) || 'file';
    const mimeType = (req.file.mimetype || 'application/octet-stream').slice(0, 100);

    try {
      let transfer = null;
      for (let attempt = 0; attempt < 5 && !transfer; attempt++) {
        try {
          const result = await pool.query(
            `INSERT INTO transfers (code, sender_user_id, sender_name, file_name, file_size, mime_type, storage_path, expires_at)
             VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP + make_interval(hours => $8))
             RETURNING code, sender_name, file_name, file_size, mime_type, expires_at`,
            [generateCode(), senderUserId, senderName, fileName, req.file.size, mimeType, req.file.filename, TRANSFER_TTL_HOURS]
          );
          transfer = result.rows[0];
        } catch (err) {
          if (err.code !== '23505') throw err; // retry only on code collision
        }
      }
      if (!transfer) throw new Error('Could not allocate a unique code');

      res.status(201).json({
        code: transfer.code,
        senderName: transfer.sender_name,
        fileName: transfer.file_name,
        fileSize: Number(transfer.file_size),
        mimeType: transfer.mime_type,
        downloadUrl: `/api/transfers/download/${transfer.code}`,
        expiresAt: transfer.expires_at
      });
    } catch (err) {
      fs.unlink(req.file.path, () => {});
      serverError(res, 'Upload error', err);
    }
  });
});

app.get('/api/transfers/:code', lookupLimiter, async (req, res) => {
  try {
    const result = await pool.query(
      `SELECT code, sender_name, file_name, file_size, mime_type, created_at, expires_at
       FROM transfers WHERE code = $1 AND expires_at > CURRENT_TIMESTAMP`,
      [cleanCode(req.params.code)]
    );
    if (result.rows.length === 0) return res.status(404).json({ error: 'No file found for this code, or it has expired.' });

    const t = result.rows[0];
    res.json({
      code: t.code,
      senderName: t.sender_name,
      fileName: t.file_name,
      fileSize: Number(t.file_size),
      mimeType: t.mime_type,
      downloadUrl: `/api/transfers/download/${t.code}`,
      createdAt: t.created_at,
      expiresAt: t.expires_at
    });
  } catch (err) {
    serverError(res, 'Lookup error', err);
  }
});

app.get('/api/transfers/download/:code', lookupLimiter, async (req, res) => {
  try {
    const result = await pool.query(
      'SELECT * FROM transfers WHERE code = $1 AND expires_at > CURRENT_TIMESTAMP',
      [cleanCode(req.params.code)]
    );
    if (result.rows.length === 0) return res.status(404).json({ error: 'No file found for this code, or it has expired.' });

    const transfer = result.rows[0];
    const filePath = path.join(UPLOAD_DIR, path.basename(transfer.storage_path));
    let stat;
    try {
      stat = fs.statSync(filePath);
    } catch {
      return res.status(404).json({ error: 'File data is missing on the server.' });
    }

    pool.query('UPDATE transfers SET download_count = download_count + 1 WHERE id = $1', [transfer.id])
      .catch((err) => console.error('download_count update failed:', err.message));

    const asciiName = transfer.file_name.replace(/[^\x20-\x7E]/g, '_').replace(/["\\]/g, '_');
    res.setHeader('Content-Disposition', `attachment; filename="${asciiName}"; filename*=UTF-8''${encodeURIComponent(transfer.file_name)}`);
    res.setHeader('Content-Type', transfer.mime_type || 'application/octet-stream');
    res.setHeader('Content-Length', stat.size);

    const stream = fs.createReadStream(filePath);
    stream.on('error', (err) => {
      console.error('Download stream error:', err.message);
      res.destroy(err);
    });
    stream.pipe(res);
  } catch (err) {
    serverError(res, 'Download error', err);
  }
});

const plans = require('./plans')(pool, settings);
plans.register(app, { authenticateToken, rateLimit, serverError });
const cleanupShares = require('./shares')(app, {
  pool, authenticateToken, optionalToken, rateLimit, serverError, UPLOAD_DIR, settings, plans, security
});
require('./account')(app, { pool, authenticateToken, rateLimit, serverError, UPLOAD_DIR });
require('./extras')(app, { pool, authenticateToken, optionalToken, rateLimit, serverError, security });
require('./admin')(app, { pool, serverError, UPLOAD_DIR, settings, plans, traffic, security, backups, APP_DIR, cleanupNow: () => cleanupExpired() });

app.use((req, res) => res.status(404).json({ error: 'Not found.' }));

// ==================== CLEANUP ====================

// Deletes expired transfers (rows and files) so the VPS disk does not fill up.
async function cleanupExpired() {
  try {
    const result = await pool.query('DELETE FROM transfers WHERE expires_at <= CURRENT_TIMESTAMP RETURNING storage_path');
    for (const row of result.rows) {
      fs.unlink(path.join(UPLOAD_DIR, path.basename(row.storage_path)), () => {});
    }
    const shareFiles = await cleanupShares();
    // Remove orphan files (e.g. an upload that crashed half-way) older than the TTL.
    const known = new Set((await pool.query('SELECT storage_path FROM transfers')).rows.map((r) => r.storage_path));
    for (const name of shareFiles) known.add(name);
    const cutoff = Date.now() - TRANSFER_TTL_HOURS * 3600 * 1000;
    for (const name of fs.readdirSync(UPLOAD_DIR)) {
      if (known.has(name)) continue;
      const full = path.join(UPLOAD_DIR, name);
      try {
        if (fs.statSync(full).mtimeMs < cutoff) fs.unlinkSync(full);
      } catch { /* ignore */ }
    }
    if (result.rowCount > 0) console.log(`Cleanup: removed ${result.rowCount} expired transfer(s).`);
  } catch (err) {
    console.error('Cleanup error:', err.message);
  }
}

// The 🔔 in the admin panel: disk almost full, suspicious activity (at most every few hours each).
async function watchServer() {
  const recent = async (kind, hours) => (await pool.query(
    `SELECT 1 FROM admin_notices WHERE kind = $1 AND at > now() - make_interval(hours => $2)`, [kind, hours])).rowCount > 0;
  try {
    const st = fs.statfsSync(UPLOAD_DIR);
    const pct = Math.round((1 - (st.bavail * st.bsize) / (st.blocks * st.bsize)) * 100);
    if (pct >= 85 && !(await recent('disk', 12))) {
      await security.notify('disk', `Disk is ${pct}% full`, 'Delete old shares or leftovers before uploads start failing.', '/admin/storage', 'owner,moderator');
    }
  } catch { /* old Node: no statfs */ }
  const alerts = await require('./alerts')(pool, settings);
  if (alerts.length && !(await recent('alert', 6))) {
    await security.notify('alert', `${alerts.length} thing(s) look suspicious`, alerts[0].what, '/admin/alerts', 'owner,moderator');
  }
}

// ==================== START ====================

initDb()
  .then(() => {
    cleanupExpired();
    setInterval(cleanupExpired, 10 * 60 * 1000).unref();
    // Plan reminders, old admin sign-ins, and the daily database backup.
    const hourly = () => {
      plans.remindPlans().catch((e) => console.error('Plan reminders:', e.message));
      pool.query('DELETE FROM admin_sessions WHERE expires_at < now()').catch(() => {});
      backups.autoBackup(settings.get('autoBackup') === 'true', settings.int('keepBackups', 7)).then((err) => {
        if (err) security.notify('backup', 'Daily backup failed', err, '/admin/backups', 'owner');
      });
      watchServer().catch((e) => console.error('Watch:', e.message));
    };
    setTimeout(hourly, 30 * 1000).unref();
    setInterval(hourly, 60 * 60 * 1000).unref();
    const server = app.listen(PORT, HOST, () => {
      console.log(`Sendit relay listening on http://${HOST}:${PORT}`);
    });
    server.requestTimeout = 0; // large uploads on slow connections must not be cut off
    server.headersTimeout = 120 * 1000;
    const shutdown = () => {
      console.log('Shutting down...');
      server.close(() => pool.end().finally(() => process.exit(0)));
      setTimeout(() => process.exit(0), 10000).unref();
    };
    process.on('SIGTERM', shutdown);
    process.on('SIGINT', shutdown);
  })
  .catch((err) => {
    console.error('FATAL: could not prepare the database:', err.message);
    process.exit(1);
  });
