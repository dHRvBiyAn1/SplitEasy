-- Users, groups, and the join table between them.
-- UUID PKs are generated application-side by Hibernate (@UuidGenerator).
-- Monetary/expense tables arrive in a later migration.

CREATE TABLE users (
    id            UUID        PRIMARY KEY,
    email         TEXT        NOT NULL UNIQUE,
    password_hash TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE groups (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    created_by UUID        NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_groups_created_by ON groups (created_by);

CREATE TABLE group_memberships (
    id        UUID        PRIMARY KEY,
    group_id  UUID        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    user_id   UUID        NOT NULL REFERENCES users (id),
    joined_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_group_memberships_group_user UNIQUE (group_id, user_id)
);

-- Fetching "my groups" filters by user_id; fetching a group's members filters
-- by group_id. Index both sides of the join.
CREATE INDEX idx_group_memberships_user ON group_memberships (user_id);
CREATE INDEX idx_group_memberships_group ON group_memberships (group_id);
