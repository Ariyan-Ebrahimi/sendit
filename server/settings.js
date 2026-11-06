'use strict';

// App-wide settings the admin can change at /admin/settings without restarting the server.
// Stored in the app_settings table; values from .env are the defaults.

module.exports = function createSettings(pool, env) {
  const defaults = {
    signupsOpen: 'true',
    announcement: '',
    quotaMb: String(env.quotaMb),
    maxFileMb: String(env.maxFileMb),
    ttlHours: String(env.ttlHours),
    blockTypesOn: 'false',
    blockedTypes: 'exe, msi, bat, cmd, com, scr, vbs, ps1, jar, apk',
    buyInfo: 'To buy a plan, write to us in Settings → Contact support. You get a code to enter here.',
    // Alerts (suspicious activity)
    alertSharesPerHour: '30',
    alertGbPerDay: '5',
    // Free trial for new accounts ('' = off)
    trialPlanId: '',
    trialDays: '7',
    // App updates: versionCode numbers; below min the app must update
    minVersion: '0',
    latestVersion: '0',
    updateUrl: '',
    updateNote: '',
    // Backups
    autoBackup: 'true',
    keepBackups: '7'
  };
  let cache = { ...defaults };

  return {
    async load() {
      const r = await pool.query('SELECT key, value FROM app_settings');
      cache = { ...defaults };
      for (const row of r.rows) if (row.key in defaults) cache[row.key] = row.value;
    },
    async set(key, value) {
      if (!(key in defaults)) return;
      await pool.query(
        `INSERT INTO app_settings (key, value) VALUES ($1, $2)
         ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value`,
        [key, String(value)]
      );
      cache[key] = String(value);
    },
    all() { return { ...cache }; },
    signupsOpen() { return cache.signupsOpen === 'true'; },
    announcement() { return cache.announcement; },
    quotaMb() { return parseInt(cache.quotaMb, 10) || env.quotaMb; },
    maxFileMb() { return parseInt(cache.maxFileMb, 10) || env.maxFileMb; },
    ttlHours() { return [1, 24, 168].includes(parseInt(cache.ttlHours, 10)) ? parseInt(cache.ttlHours, 10) : 24; },
    buyInfo() { return cache.buyInfo; },
    int(key, d = 0) { const n = parseInt(cache[key], 10); return Number.isFinite(n) ? n : d; },
    get(key) { return cache[key]; },
    /** Lower-case extensions that may not be uploaded ([] when the switch is off). */
    blockedTypes() {
      if (cache.blockTypesOn !== 'true') return [];
      return cache.blockedTypes.split(/[\s,;]+/).map((x) => x.replace(/^\./, '').toLowerCase()).filter(Boolean);
    }
  };
};
