-- Private saved split rules (P05): ratio types only (EQUAL set, PERCENTAGE, WEIGHTS). Additive;
-- old backends ignore the table. Rollback keeps the schema (forward fix only).
CREATE TABLE split_rules (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    split_type VARCHAR(12) NOT NULL CHECK (split_type IN ('EQUAL','PERCENTAGE','WEIGHTS')),
    inputs JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ
);
-- Serves the owner's list per group and keeps live names unique per owner and group.
CREATE UNIQUE INDEX uq_split_rules_owner_group_name
    ON split_rules(owner_id, group_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX idx_split_rules_group ON split_rules(group_id);
