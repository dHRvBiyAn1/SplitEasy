-- Settle-up payments: a direct transfer of money from one member to another,
-- netted into balances alongside expenses. Amounts are integer cents.
CREATE TABLE payments (
    id           UUID        PRIMARY KEY,
    group_id     UUID        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    payer_id     UUID        NOT NULL REFERENCES users (id),
    payee_id     UUID        NOT NULL REFERENCES users (id),
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_payments_payer_not_payee CHECK (payer_id <> payee_id)
);

CREATE INDEX idx_payments_group ON payments (group_id);
CREATE INDEX idx_payments_payer ON payments (payer_id);
CREATE INDEX idx_payments_payee ON payments (payee_id);
