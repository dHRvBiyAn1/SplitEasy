-- Per-group "simplify debts" preference, shown in the group's Advanced settings and
-- applied for every member (it changes which payments settle-up suggests, so it is a
-- property of the group rather than of the viewer). Defaults to on, matching the
-- behaviour settle-up shipped with.
ALTER TABLE groups ADD COLUMN simplify_debts BOOLEAN NOT NULL DEFAULT TRUE;
