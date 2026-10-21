ALTER TABLE expenses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Response snapshots deliberately survive resource deletion; a retry must never recreate it.
CREATE TABLE create_operations (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    scope_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    intent TEXT NOT NULL,
    response TEXT,
    PRIMARY KEY (actor_id, kind, scope_id, operation_id)
);
