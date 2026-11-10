-- Apply V14 before C01 V15, or ship together. Keep schema on binary rollback.
CREATE TABLE auth_sessions (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  credential_version BIGINT NOT NULL CHECK (credential_version >= 0),
  device_label VARCHAR(80) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  refreshed_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  idle_expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  rotations INTEGER NOT NULL DEFAULT 0 CHECK (rotations BETWEEN 0 AND 2048)
);
CREATE INDEX idx_auth_sessions_user_created ON auth_sessions(user_id, created_at DESC, id);

CREATE TABLE refresh_credentials (
  token_hash CHAR(64) PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
  consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_credentials_session ON refresh_credentials(session_id);
