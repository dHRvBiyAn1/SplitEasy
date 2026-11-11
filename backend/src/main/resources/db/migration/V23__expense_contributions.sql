-- E03. Additive. Money each financial participant actually paid towards an expense, distinct
-- from expense_participants (what each owes). Contributions sum to the expense amount.
-- Drain old backends first: they insert expenses without a contribution row, and readers now
-- sum paid amounts from this table. expenses.paid_by stays as the primary payer (rounding
-- recipient). Rollback after a multi-payer write is a forward fix, never an old binary.

CREATE TABLE expense_contributions (
    id             UUID PRIMARY KEY,
    expense_id     UUID   NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    participant_id UUID   NOT NULL REFERENCES participants (id),
    amount_cents   BIGINT NOT NULL CHECK (amount_cents > 0),
    CONSTRAINT uq_expense_contributions_expense_participant UNIQUE (expense_id, participant_id)
);
CREATE INDEX idx_expense_contributions_participant ON expense_contributions (participant_id);

-- Every existing expense, deleted ones included so restore keeps working, had one payer.
INSERT INTO expense_contributions (id, expense_id, participant_id, amount_cents)
SELECT gen_random_uuid(), id, paid_by, amount_cents FROM expenses;
