-- Composite indexes for the hottest read paths. Each replaces a now-redundant
-- single-column group index: a composite (group_id, <sort cols>) serves the
-- filter AND the sort in one scan, and its leftmost prefix (group_id alone) still
-- covers the plain group_id filters used by the balance aggregate queries.

-- Expenses — group detail list + dashboard recent-activity feed:
--   WHERE group_id = ? ORDER BY spent_on DESC, created_at DESC
--   (ExpenseRepository.findSummariesByGroupId, findRecentByGroupIds)
DROP INDEX IF EXISTS idx_expenses_group;
CREATE INDEX idx_expenses_group_spent_on ON expenses (group_id, spent_on DESC, created_at DESC);

-- Payments — settle-up history + dashboard recent-activity feed:
--   WHERE group_id = ? ORDER BY created_at DESC
--   (PaymentRepository.findByGroupIdFetchUsers, findRecentByGroupIds)
DROP INDEX IF EXISTS idx_payments_group;
CREATE INDEX idx_payments_group_created_at ON payments (group_id, created_at DESC);
