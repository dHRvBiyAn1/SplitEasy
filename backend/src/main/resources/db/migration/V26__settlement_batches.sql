-- Atomic cross-group friend settlement (B02): one claim row per keyed batch plus the payments it
-- created. Payments stay ordinary ledger rows, so each group history explains itself. Additive;
-- keep this schema on rollback.
CREATE TABLE settlement_batches (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    friend_account_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    currency VARCHAR(3) NOT NULL,
    operation_id UUID NOT NULL,
    intent JSONB NOT NULL,
    response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_settlement_batches_actor_operation UNIQUE (actor_id, operation_id)
);
CREATE INDEX idx_settlement_batches_friend ON settlement_batches(friend_account_id);

CREATE TABLE settlement_allocations (
    batch_id UUID NOT NULL REFERENCES settlement_batches(id) ON DELETE CASCADE,
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    PRIMARY KEY (batch_id, payment_id)
);
CREATE INDEX idx_settlement_allocations_payment ON settlement_allocations(payment_id);
CREATE INDEX idx_settlement_allocations_group ON settlement_allocations(group_id);
