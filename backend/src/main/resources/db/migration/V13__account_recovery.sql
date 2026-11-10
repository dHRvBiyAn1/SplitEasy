-- Promote E02 V12 before V13, or include both in the same release.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0);

CREATE TABLE account_challenges (
  token_hash CHAR(64) PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('PASSWORD_RESET','EMAIL_VERIFICATION','EMAIL_CHANGE')),
  target_email VARCHAR(254) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_account_challenges_user_purpose UNIQUE (user_id,purpose)
);
