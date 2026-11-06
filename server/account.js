'use strict';

// Account extras: forgot password (6-digit code by email), and deleting your own account.
//
// Email is sent through SMTP when SMTP_HOST is set in .env. Without SMTP, the code is kept on the
// server and shown on the admin page (/admin), so the admin can pass it on to the user.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');

const RESET_MINUTES = 15;
const MAX_CODE_ATTEMPTS = 5;

module.exports = function registerAccount(app, deps) {
  const { pool, authenticateToken, rateLimit, serverError, UPLOAD_DIR } = deps;

  const forgotLimiter = rateLimit({ windowMs: 60 * 60 * 1000, max: 10, message: 'Too many reset requests. Please try again later.' });
  const resetLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 30, message: 'Too many attempts. Please wait a few minutes and try again.' });

  let mailer = null;
  if (process.env.SMTP_HOST) {
    try {
      const nodemailer = require('nodemailer');
      mailer = nodemailer.createTransport({
        host: process.env.SMTP_HOST,
        port: parseInt(process.env.SMTP_PORT, 10) || 587,
        secure: (parseInt(process.env.SMTP_PORT, 10) || 587) === 465,
        auth: process.env.SMTP_USER ? { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS } : undefined
      });
      console.log('Password reset emails: SMTP enabled.');
    } catch (err) {
      console.error('SMTP setup failed:', err.message);
    }
  } else {
    console.log('Password reset emails: SMTP not configured, codes appear on the admin page.');
  }

  async function sendResetEmail(to, name, code) {
    if (!mailer) return false;
    try {
      await mailer.sendMail({
        from: process.env.SMTP_FROM || process.env.SMTP_USER,
        to,
        subject: `Sendit password reset code: ${code}`,
        text: `Hi ${name},\n\nYour Sendit password reset code is: ${code}\n\nIt works for ${RESET_MINUTES} minutes. If you didn't ask for this, you can ignore this email.\n\n— Sendit`
      });
      return true;
    } catch (err) {
      console.error('Reset email failed:', err.message);
      return false;
    }
  }

  // Step 1: ask for a code. Always answers the same way, so nobody can test which emails exist.
  app.post('/api/auth/forgot', forgotLimiter, async (req, res) => {
    try {
      const email = String(req.body.email || '').trim().toLowerCase();
      const r = await pool.query('SELECT id, name, email FROM users WHERE email = $1', [email]);
      const user = r.rows[0];
      if (user) {
        const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
        await pool.query('UPDATE password_resets SET used = TRUE WHERE user_id = $1 AND NOT used', [user.id]);
        const emailed = await sendResetEmail(user.email, user.name, code);
        await pool.query(
          `INSERT INTO password_resets (user_id, code, emailed, expires_at)
           VALUES ($1, $2, $3, CURRENT_TIMESTAMP + make_interval(mins => $4))`,
          [user.id, code, emailed, RESET_MINUTES]
        );
        if (!emailed) console.log(`Password reset requested for ${user.email} (see /admin for the code).`);
      }
      res.json({ ok: true, emailEnabled: !!mailer });
    } catch (err) {
      serverError(res, 'Forgot password error', err);
    }
  });

  // Step 2: code + new password.
  app.post('/api/auth/reset', resetLimiter, async (req, res) => {
    try {
      const email = String(req.body.email || '').trim().toLowerCase();
      const code = String(req.body.code || '').trim();
      const password = String(req.body.password || '');
      if (password.length < 6) return res.status(400).json({ error: 'Password must be at least 6 characters.' });

      const r = await pool.query(
        `SELECT pr.id, pr.code, pr.attempts, u.id AS user_id
         FROM password_resets pr JOIN users u ON u.id = pr.user_id
         WHERE u.email = $1 AND NOT pr.used AND pr.expires_at > CURRENT_TIMESTAMP
         ORDER BY pr.created_at DESC LIMIT 1`,
        [email]
      );
      const reset = r.rows[0];
      const bad = () => res.status(400).json({ error: 'That code is wrong or has expired. Ask for a new one.' });
      if (!reset) return bad();
      if (reset.attempts >= MAX_CODE_ATTEMPTS) {
        await pool.query('UPDATE password_resets SET used = TRUE WHERE id = $1', [reset.id]);
        return bad();
      }
      const ok = reset.code.length === code.length && crypto.timingSafeEqual(Buffer.from(reset.code), Buffer.from(code));
      if (!ok) {
        await pool.query('UPDATE password_resets SET attempts = attempts + 1 WHERE id = $1', [reset.id]);
        return bad();
      }
      const hash = await bcrypt.hash(password, 10);
      await pool.query('UPDATE users SET password_hash = $1 WHERE id = $2', [hash, reset.user_id]);
      await pool.query('UPDATE password_resets SET used = TRUE WHERE user_id = $1', [reset.user_id]);
      res.json({ ok: true });
    } catch (err) {
      serverError(res, 'Reset password error', err);
    }
  });

  // Delete my account: needs the password again. Removes all my shares and their files.
  app.post('/api/auth/delete-account', resetLimiter, authenticateToken, async (req, res) => {
    try {
      const password = String(req.body.password || '');
      const r = await pool.query('SELECT id, password_hash FROM users WHERE id = $1', [req.user.id]);
      const user = r.rows[0];
      if (!user) return res.status(404).json({ error: 'User not found.' });
      if (!(await bcrypt.compare(password, user.password_hash))) return res.status(401).json({ error: 'Wrong password.' });
      await deleteUserCompletely(pool, UPLOAD_DIR, user.id);
      res.json({ ok: true });
    } catch (err) {
      serverError(res, 'Delete account error', err);
    }
  });
};

/** Removes a user, every share they made (with files) and their transfers. Shared with the admin page. */
async function deleteUserCompletely(pool, uploadDir, userId) {
  const files = await pool.query(
    `SELECT f.storage_path FROM share_files f JOIN shares s ON s.id = f.share_id WHERE s.sender_user_id = $1
     UNION ALL SELECT storage_path FROM transfers WHERE sender_user_id = $1`,
    [userId]
  );
  await pool.query('DELETE FROM shares WHERE sender_user_id = $1', [userId]); // share_files cascade
  await pool.query('DELETE FROM transfers WHERE sender_user_id = $1', [userId]);
  await pool.query('DELETE FROM users WHERE id = $1', [userId]);
  for (const row of files.rows) {
    if (row.storage_path) fs.unlink(path.join(uploadDir, path.basename(row.storage_path)), () => {});
  }
}

module.exports.deleteUserCompletely = deleteUserCompletely;
