-- PulseMesh Free Atomic & Zero-Knowledge Encrypted Username Relay (Cloudflare D1 / SQLite)

CREATE TABLE IF NOT EXISTS users (
    username TEXT PRIMARY KEY,
    node_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    public_key_b64 TEXT NOT NULL,
    salt_hex TEXT NOT NULL,
    pin_vault TEXT NOT NULL,
    recovery_vault TEXT NOT NULL,
    auth_verifier_hash TEXT NOT NULL,
    unlock_verifier_hash TEXT NOT NULL DEFAULT '',
    signature_b64 TEXT NOT NULL,
    claimed_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    backup_blob TEXT NOT NULL DEFAULT '',
    backup_updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS inbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    recipient TEXT NOT NULL,
    envelope TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_inbox_recipient_id ON inbox (recipient, id);
CREATE INDEX IF NOT EXISTS idx_inbox_created_at ON inbox (created_at);

CREATE TABLE IF NOT EXISTS rate_limits (
    bucket TEXT PRIMARY KEY,
    hits INTEGER NOT NULL,
    window_start INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_challenges (
    nonce TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires_at ON auth_challenges (expires_at);

CREATE TABLE IF NOT EXISTS vault_lockouts (
    username TEXT PRIMARY KEY,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER NOT NULL DEFAULT 0
);
