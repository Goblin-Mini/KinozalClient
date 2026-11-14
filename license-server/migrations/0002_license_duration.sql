-- Run this migration ONCE in the existing Cloudflare D1 database console.
-- Existing licenses stay lifetime because duration_days defaults to 0 and expires_at stays NULL.
ALTER TABLE licenses ADD COLUMN duration_days INTEGER NOT NULL DEFAULT 0;
ALTER TABLE licenses ADD COLUMN expires_at TEXT;
