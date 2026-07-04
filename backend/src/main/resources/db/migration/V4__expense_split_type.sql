-- How each expense was split. Existing rows were all equal splits.
-- Per-participant values still live in expense_participants.share_cents (no new column).
ALTER TABLE expenses ADD COLUMN split_type TEXT NOT NULL DEFAULT 'EQUAL';
