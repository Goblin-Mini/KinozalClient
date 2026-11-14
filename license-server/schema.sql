PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS licenses (
  id TEXT PRIMARY KEY,
  customer_label TEXT,
  plan TEXT NOT NULL DEFAULT 'plus_lifetime'
    CHECK (plan IN ('plus_lifetime', 'owner')),
  status TEXT NOT NULL DEFAULT 'active'
    CHECK (status IN ('active', 'revoked')),
  device_limit INTEGER NOT NULL DEFAULT 3,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS activation_codes (
  id TEXT PRIMARY KEY,
  license_id TEXT NOT NULL REFERENCES licenses(id) ON DELETE CASCADE,
  code_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT,
  encrypted_code TEXT,
  encryption_iv TEXT,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  revoked_at TEXT
);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  license_id TEXT NOT NULL REFERENCES licenses(id) ON DELETE CASCADE,
  installation_id TEXT NOT NULL,
  device_label TEXT,
  token_hash TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  last_seen_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  revoked_at TEXT,
  UNIQUE(license_id, installation_id)
);

CREATE INDEX IF NOT EXISTS idx_activation_codes_license
  ON activation_codes(license_id);
CREATE INDEX IF NOT EXISTS idx_devices_license_active
  ON devices(license_id, revoked_at);
CREATE INDEX IF NOT EXISTS idx_devices_installation
  ON devices(installation_id);


-- Per-item sync records. Tombstones are retained so deletions propagate to
-- devices that were offline when an item was removed.
CREATE TABLE IF NOT EXISTS sync_items (
  license_id TEXT NOT NULL REFERENCES licenses(id) ON DELETE CASCADE,
  item_type TEXT NOT NULL CHECK (item_type IN ('favorite', 'history')),
  item_key TEXT NOT NULL,
  payload TEXT NOT NULL DEFAULT '{}',
  is_deleted INTEGER NOT NULL DEFAULT 0 CHECK (is_deleted IN (0, 1)),
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  PRIMARY KEY (license_id, item_type, item_key)
);

CREATE INDEX IF NOT EXISTS idx_sync_items_license_updated
  ON sync_items(license_id, updated_at);
