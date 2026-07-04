-- Expenses and their per-participant equal-split shares.
-- Monetary values are integer cents (BIGINT) — never floating point.
-- UUID PKs are generated application-side by Hibernate (@UuidGenerator).

CREATE TABLE expenses (
    id           UUID        PRIMARY KEY,
    group_id     UUID        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    description  TEXT        NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    paid_by      UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL
);

-- Listing expenses filters by group and orders by recency.
CREATE INDEX idx_expenses_group ON expenses (group_id);
CREATE INDEX idx_expenses_paid_by ON expenses (paid_by);

CREATE TABLE expense_participants (
    id          UUID   PRIMARY KEY,
    expense_id  UUID   NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    user_id     UUID   NOT NULL REFERENCES users (id),
    -- What this participant owes for this expense. The shares of an expense
    -- always sum exactly to expenses.amount_cents (payer absorbs remainder cents).
    share_cents BIGINT NOT NULL CHECK (share_cents >= 0),
    CONSTRAINT uq_expense_participants_expense_user UNIQUE (expense_id, user_id)
);

CREATE INDEX idx_expense_participants_expense ON expense_participants (expense_id);
CREATE INDEX idx_expense_participants_user ON expense_participants (user_id);
