-- Archive stops new financial entries; delete becomes a recoverable creator-only state.
-- Drain old backends first: they ignore these columns and would write into an archived or
-- deleted group and list it as active. Keep this schema on rollback and use a forward fix.
ALTER TABLE groups ADD COLUMN archived_at TIMESTAMPTZ;
ALTER TABLE groups ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE groups ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 CHECK (lifecycle_version >= 0);

-- Serves the creator's recovery list; existing rows stay active with version 0.
CREATE INDEX idx_groups_deleted_created_by ON groups(created_by, deleted_at DESC, id) WHERE deleted_at IS NOT NULL;
