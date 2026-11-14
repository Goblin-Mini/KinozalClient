-- Apply exactly once to the existing D1 database.
-- This migration only adds nullable encrypted-code columns; it preserves all existing records.
ALTER TABLE activation_codes ADD COLUMN encrypted_code TEXT;
ALTER TABLE activation_codes ADD COLUMN encryption_iv TEXT;
