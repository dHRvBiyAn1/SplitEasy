-- Descriptive metadata for the dashboard redesign: a group "type", and per-expense
-- "category" + user-chosen "spent_on" date. All additive with safe defaults so existing
-- rows and the existing API keep working.

ALTER TABLE groups ADD COLUMN type TEXT NOT NULL DEFAULT 'OTHER';

ALTER TABLE expenses ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER';
ALTER TABLE expenses ADD COLUMN spent_on DATE NOT NULL DEFAULT CURRENT_DATE;

-- Backfill spent_on for existing expenses from when they were recorded.
UPDATE expenses SET spent_on = (created_at AT TIME ZONE 'UTC')::date;
