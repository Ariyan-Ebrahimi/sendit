-- Schema for the Sendit relay server (PostgreSQL). Safe to run many times.

CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transfers (
    id SERIAL PRIMARY KEY,
    code VARCHAR(16) UNIQUE NOT NULL,
    sender_user_id INT REFERENCES users(id) ON DELETE SET NULL,
    sender_name VARCHAR(100) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    storage_path TEXT NOT NULL,
    download_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE DEFAULT (CURRENT_TIMESTAMP + INTERVAL '24 hours')
);

CREATE INDEX IF NOT EXISTS idx_transfers_code ON transfers(code);
CREATE INDEX IF NOT EXISTS idx_transfers_sender ON transfers(sender_user_id);
CREATE INDEX IF NOT EXISTS idx_transfers_expires ON transfers(expires_at);

-- "Anywhere" shares: one code, many files.
CREATE TABLE IF NOT EXISTS shares (
    id SERIAL PRIMARY KEY,
    code VARCHAR(16) UNIQUE NOT NULL,
    owner_key VARCHAR(64) NOT NULL,
    sender_user_id INT REFERENCES users(id) ON DELETE SET NULL,
    sender_name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'uploading',
    download_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS share_files (
    id SERIAL PRIMARY KEY,
    share_id INT NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
    idx INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL,
    mime VARCHAR(100) NOT NULL,
    storage_path TEXT,
    uploaded BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (share_id, idx)
);

CREATE INDEX IF NOT EXISTS idx_shares_expires ON shares(expires_at);

-- v1.2: resumable uploads, passwords, expiry choice, one-time shares, sending to a person.
ALTER TABLE shares ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE shares ADD COLUMN IF NOT EXISTS one_time BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS recipient_user_id INT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE share_files ADD COLUMN IF NOT EXISTS completed_downloads INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_shares_recipient ON shares(recipient_user_id);
CREATE INDEX IF NOT EXISTS idx_shares_sender ON shares(sender_user_id);

-- v1.3: storage limit per user, password reset codes.
ALTER TABLE users ADD COLUMN IF NOT EXISTS quota_mb INT;
CREATE TABLE IF NOT EXISTS password_resets (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code VARCHAR(6) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    emailed BOOLEAN NOT NULL DEFAULT FALSE,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_password_resets_user ON password_resets(user_id);

-- v1.4: admin-managed settings, blocked users.
CREATE TABLE IF NOT EXISTS app_settings (
    key VARCHAR(64) PRIMARY KEY,
    value TEXT NOT NULL
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS banned BOOLEAN NOT NULL DEFAULT FALSE;

-- v1.5: share message, contacts, file requests, crash reports, broadcasts, chart events.
ALTER TABLE shares ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS request_id INT;
CREATE TABLE IF NOT EXISTS contacts (
    id SERIAL PRIMARY KEY,
    owner_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auto_accept BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, contact_id)
);
CREATE TABLE IF NOT EXISTS file_requests (
    id SERIAL PRIMARY KEY,
    code VARCHAR(16) UNIQUE NOT NULL,
    owner_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120),
    uploads INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE IF NOT EXISTS crash_reports (
    id SERIAL PRIMARY KEY,
    user_id INT REFERENCES users(id) ON DELETE SET NULL,
    app_version VARCHAR(40),
    device VARCHAR(120),
    android VARCHAR(40),
    stack TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS broadcasts (
    id SERIAL PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(20) NOT NULL,
    user_id INT,
    bytes BIGINT NOT NULL DEFAULT 0,
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_events_at ON events(at);

-- v1.6: send later, groups, notices (downloaded / expiring / support), support chat.
ALTER TABLE shares ADD COLUMN IF NOT EXISTS release_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS reminded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS last_download_notice TIMESTAMP WITH TIME ZONE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS group_name VARCHAR(80);
CREATE TABLE IF NOT EXISTS share_recipients (
    share_id INT NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (share_id, user_id)
);
CREATE TABLE IF NOT EXISTS contact_groups (
    id SERIAL PRIMARY KEY,
    owner_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS contact_group_members (
    group_id INT NOT NULL REFERENCES contact_groups(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);
CREATE TABLE IF NOT EXISTS notices (
    id BIGSERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    code VARCHAR(16),
    name VARCHAR(255),
    count INT NOT NULL DEFAULT 0,
    body TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_notices_user ON notices(user_id, id);
CREATE TABLE IF NOT EXISTS support_messages (
    id SERIAL PRIMARY KEY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_admin BOOLEAN NOT NULL DEFAULT FALSE,
    body TEXT NOT NULL,
    seen_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_support_user ON support_messages(user_id, id);

-- v1.7: view once, who downloaded, send to my devices.
ALTER TABLE shares ADD COLUMN IF NOT EXISTS view_once BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS sender_device VARCHAR(64);
CREATE TABLE IF NOT EXISTS share_downloads (
    share_id INT NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (share_id, user_id)
);

-- v1.8: plans + vouchers, end-to-end encryption, place-locked and self-destructing shares, live admin.
CREATE TABLE IF NOT EXISTS plans (
    id SERIAL PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    quota_mb INT NOT NULL,
    max_file_mb INT NOT NULL,
    max_ttl_hours INT NOT NULL DEFAULT 720,
    price_text VARCHAR(120),
    sort INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO plans (name, quota_mb, max_file_mb, max_ttl_hours, price_text, sort)
SELECT * FROM (VALUES ('Plus', 20480, 4096, 720, '', 1), ('Pro', 102400, 20480, 720, '', 2)) v
WHERE NOT EXISTS (SELECT 1 FROM plans);
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_id INT REFERENCES plans(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen TIMESTAMP WITH TIME ZONE;
CREATE TABLE IF NOT EXISTS vouchers (
    code VARCHAR(20) PRIMARY KEY,
    plan_id INT NOT NULL REFERENCES plans(id) ON DELETE CASCADE,
    days INT NOT NULL,
    note VARCHAR(120),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    used_by INT REFERENCES users(id) ON DELETE SET NULL,
    used_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS device_keys (
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,
    public_key TEXT NOT NULL,
    name VARCHAR(60),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, device_id)
);
CREATE TABLE IF NOT EXISTS share_keys (
    share_id INT NOT NULL REFERENCES shares(id) ON DELETE CASCADE,
    device_id VARCHAR(64) NOT NULL,
    user_id INT,
    wrapped TEXT NOT NULL,
    PRIMARY KEY (share_id, device_id)
);
ALTER TABLE shares ADD COLUMN IF NOT EXISTS e2e BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS e2e_meta TEXT;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS e2e_epk TEXT;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS destruct_seconds INT;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS geo_lat DOUBLE PRECISION;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS geo_lng DOUBLE PRECISION;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS geo_radius INT;

-- v1.9: admin accounts + roles, sign-in sessions, activity log, IP bans, reports, banned files,
-- multi-use / trial codes, plan reminders, app-update settings.
CREATE TABLE IF NOT EXISTS admins (
    id SERIAL PRIMARY KEY,
    username VARCHAR(40) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'support',
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP WITH TIME ZONE
);
CREATE TABLE IF NOT EXISTS admin_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    admin_id INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    ip VARCHAR(64),
    agent VARCHAR(200)
);
CREATE TABLE IF NOT EXISTS admin_log (
    id BIGSERIAL PRIMARY KEY,
    admin_name VARCHAR(40),
    role VARCHAR(20),
    action VARCHAR(120) NOT NULL,
    target VARCHAR(120),
    detail TEXT,
    ip VARCHAR(64),
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS admin_log_at ON admin_log (at DESC);
CREATE TABLE IF NOT EXISTS ip_bans (
    ip VARCHAR(64) PRIMARY KEY,
    reason VARCHAR(200),
    banned_by VARCHAR(40),
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_ip VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS signup_ip VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS app_version VARCHAR(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_reminded TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_end_noticed TIMESTAMP WITH TIME ZONE;
CREATE TABLE IF NOT EXISTS reports (
    id SERIAL PRIMARY KEY,
    share_id INT REFERENCES shares(id) ON DELETE SET NULL,
    code VARCHAR(16),
    reason VARCHAR(40) NOT NULL,
    details TEXT,
    reporter_id INT REFERENCES users(id) ON DELETE SET NULL,
    reporter_ip VARCHAR(64),
    status VARCHAR(12) NOT NULL DEFAULT 'open',
    handled_by VARCHAR(40),
    handled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE shares ADD COLUMN IF NOT EXISTS blocked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE shares ADD COLUMN IF NOT EXISTS blocked_reason VARCHAR(200);
ALTER TABLE share_files ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);
CREATE INDEX IF NOT EXISTS share_files_sha ON share_files (sha256);
CREATE TABLE IF NOT EXISTS banned_hashes (
    sha256 VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255),
    reason VARCHAR(200),
    banned_by VARCHAR(40),
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS price_amount INT NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS max_uses INT NOT NULL DEFAULT 1;
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS uses INT NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS value INT NOT NULL DEFAULT 0;
UPDATE vouchers SET uses = 1 WHERE used_by IS NOT NULL AND uses = 0;
CREATE TABLE IF NOT EXISTS voucher_uses (
    code VARCHAR(20) NOT NULL REFERENCES vouchers(code) ON DELETE CASCADE,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (code, user_id)
);
INSERT INTO voucher_uses (code, user_id, at) SELECT code, used_by, COALESCE(used_at, now()) FROM vouchers WHERE used_by IS NOT NULL ON CONFLICT DO NOTHING;
CREATE INDEX IF NOT EXISTS events_at ON events (at);

-- v1.10: admin UI — per-admin preferences, notification bell, saved support replies, solved chats.
CREATE TABLE IF NOT EXISTS admin_prefs (
    admin VARCHAR(40) NOT NULL,
    key VARCHAR(40) NOT NULL,
    value JSONB,
    PRIMARY KEY (admin, key)
);
CREATE TABLE IF NOT EXISTS admin_notices (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(500),
    link VARCHAR(200),
    roles VARCHAR(60) NOT NULL DEFAULT 'owner',
    at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS support_replies (
    id SERIAL PRIMARY KEY,
    title VARCHAR(60) NOT NULL,
    body TEXT NOT NULL
);
ALTER TABLE users ADD COLUMN IF NOT EXISTS support_solved BOOLEAN NOT NULL DEFAULT FALSE;
