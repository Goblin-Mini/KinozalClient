-- Add encrypted activation-code storage to an existing D1 database.
-- Run once only; SQLite does not support ADD COLUMN IF NOT EXISTS.
ALTER TABLE activation_codes ADD COLUMN encrypted_code TEXT;
ALTER TABLE activation_codes ADD COLUMN encryption_iv TEXT;
