ALTER TABLE invites ADD COLUMN max_devices INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS invite_devices (
  id TEXT PRIMARY KEY,
  invite_id TEXT NOT NULL,
  device_id TEXT NOT NULL UNIQUE,
  device_public_key TEXT NOT NULL,
  key_alg TEXT NOT NULL,
  app_version TEXT,
  bound_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  FOREIGN KEY (invite_id) REFERENCES invites(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_invite_devices_pubkey
  ON invite_devices(invite_id, device_public_key, key_alg);

CREATE INDEX IF NOT EXISTS idx_invite_devices_invite
  ON invite_devices(invite_id);

CREATE INDEX IF NOT EXISTS idx_invite_devices_last_seen
  ON invite_devices(last_seen_at DESC);

INSERT OR IGNORE INTO invite_devices(
  id,
  invite_id,
  device_id,
  device_public_key,
  key_alg,
  app_version,
  bound_at,
  last_seen_at
)
SELECT
  lower(hex(randomblob(16))),
  id,
  device_id,
  device_public_key,
  key_alg,
  app_version,
  COALESCE(bound_at, created_at),
  COALESCE(last_seen_at, bound_at, created_at)
FROM invites
WHERE device_id IS NOT NULL
  AND device_public_key IS NOT NULL
  AND key_alg IS NOT NULL;

UPDATE invites
SET device_id = NULL,
    device_public_key = NULL,
    key_alg = NULL
WHERE device_id IS NOT NULL;
