'use strict';

// Database backups: pg_dump → gzip → server/backups/sendit-YYYY-MM-DD-HHMM.sql.gz
// One is made automatically every day (when switched on); the newest ones are kept.
// Uploaded files are not included (they are temporary and can be very big).

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { spawn } = require('child_process');

const DIR = process.env.BACKUP_DIR || path.join(__dirname, 'backups');
const NAME = /^sendit-[0-9-]+\.sql\.gz$/;

function list() {
  try {
    return fs.readdirSync(DIR).filter((n) => NAME.test(n)).map((name) => {
      const st = fs.statSync(path.join(DIR, name));
      return { name, size: st.size, at: st.mtime };
    }).sort((a, b) => b.at - a.at);
  } catch { return []; }
}

function filePath(name) {
  if (!NAME.test(String(name))) return null;
  const full = path.join(DIR, path.basename(name));
  return fs.existsSync(full) ? full : null;
}

/** Makes a backup now. Resolves with its info; rejects with a readable message. */
function make() {
  return new Promise((resolve, reject) => {
    fs.mkdirSync(DIR, { recursive: true });
    const stamp = new Date().toISOString().slice(0, 19).replace(/[T:]/g, '-');
    const name = `sendit-${stamp}.sql.gz`;
    const full = path.join(DIR, name);
    const bin = process.env.PG_DUMP || 'pg_dump';
    let errText = '';
    let child;
    try {
      child = spawn(bin, ['--no-owner', '--no-privileges', process.env.DATABASE_URL], { windowsHide: true });
    } catch (e) {
      return reject(new Error(`Could not start pg_dump (${e.message}). Set PG_DUMP in .env.`));
    }
    const out = fs.createWriteStream(full);
    // The file may finish closing before or after pg_dump exits: wait for both, in any order.
    const written = new Promise((done) => out.on('close', done));
    child.stdout.pipe(zlib.createGzip()).pipe(out);
    child.stderr.on('data', (d) => { errText += d.toString(); });
    child.on('error', (e) => {
      fs.unlink(full, () => {});
      reject(new Error(e.code === 'ENOENT' ? 'pg_dump was not found on the server. Install PostgreSQL client tools or set PG_DUMP in .env.' : e.message));
    });
    child.on('close', async (code) => {
      await written;
      if (code !== 0) {
        fs.unlink(full, () => {});
        return reject(new Error(`pg_dump failed: ${errText.trim().slice(0, 300) || 'exit ' + code}`));
      }
      resolve({ name, size: fs.statSync(full).size });
    });
  });
}

function remove(name) {
  const full = filePath(name);
  if (full) fs.unlinkSync(full);
}

/** Keeps only the newest [keep] backups. */
function prune(keep) {
  list().slice(keep).forEach((b) => { try { fs.unlinkSync(path.join(DIR, b.name)); } catch { /* gone */ } });
}

/** Daily backup if the newest one is older than 24 hours. Resolves with an error message when it failed. */
async function autoBackup(enabled, keep) {
  if (!enabled) return null;
  const newest = list()[0];
  if (newest && Date.now() - newest.at.getTime() < 23.5 * 3600 * 1000) return null;
  try {
    const b = await make();
    console.log('Daily backup:', b.name);
    prune(keep);
    return null;
  } catch (e) {
    console.error('Daily backup failed:', e.message);
    return e.message;
  }
}

module.exports = { list, filePath, make, remove, prune, autoBackup, DIR };
