-- Keep original typed inputs alongside final owed cents. Nullable permits old writers during rollout.
ALTER TABLE expense_participants ADD COLUMN split_value BIGINT;
UPDATE expense_participants p
SET split_value = CASE WHEN e.split_type = 'EQUAL' THEN 1 ELSE p.share_cents END
FROM expenses e WHERE e.id = p.expense_id;
-- Original legacy percentages were discarded. Exact shares are the only honest editable intent.
UPDATE expenses SET split_type = 'UNEQUAL' WHERE split_type = 'PERCENTAGE';
