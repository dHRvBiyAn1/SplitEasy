-- B01. Additive. Drain old backends first: their payment aggregates have no deleted_at filter
-- and cannot see refunds, so they would count a deleted payment and ignore a refund.
-- Keep this schema on rollback; recovery is a forward fix, never an old payment-reading binary.

ALTER TABLE payments
    ADD COLUMN paid_on DATE,
    ADD COLUMN method VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN note VARCHAR(280),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_payments_method
        CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CARD', 'ONLINE', 'OTHER')),
    ADD CONSTRAINT chk_payments_version CHECK (version >= 0);

-- V9 could only attribute a keyed payment. B01 records the real recorder on every new payment,
-- so the pairing relaxes to "a keyed row is always attributable". Legacy rows stay fully null.
ALTER TABLE payments
    DROP CONSTRAINT chk_payments_operation_identity,
    ADD CONSTRAINT chk_payments_operation_needs_recorder
        CHECK (operation_id IS NULL OR recorded_by IS NOT NULL);

-- A pre-B01 settlement has no user-chosen date; its server timestamp is the honest answer.
UPDATE payments SET paid_on = (created_at AT TIME ZONE 'UTC')::date WHERE paid_on IS NULL;
ALTER TABLE payments ALTER COLUMN paid_on SET NOT NULL;

-- Serves the ordered financial-date history; idx_payments_group_created_at still serves the feed.
CREATE INDEX idx_payments_group_paid_on ON payments (group_id, paid_on DESC, created_at DESC);
CREATE INDEX idx_payments_deleted ON payments (group_id, deleted_at DESC, id)
    WHERE deleted_at IS NOT NULL;

-- Money returned out of one expense. Amounts stay positive; the reader subtracts these rows.
CREATE TABLE expense_refunds (
    id           UUID        PRIMARY KEY,
    expense_id   UUID        NOT NULL REFERENCES expenses (id) ON DELETE CASCADE,
    group_id     UUID        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    currency     VARCHAR(3)  NOT NULL,
    amount_cents BIGINT      NOT NULL CHECK (amount_cents > 0),
    received_by  UUID        NOT NULL REFERENCES participants (id),
    refunded_on  DATE        NOT NULL,
    note         VARCHAR(280),
    recorded_by  UUID        REFERENCES users (id),
    operation_id UUID,
    created_at   TIMESTAMPTZ NOT NULL,
    version      BIGINT      NOT NULL DEFAULT 0 CHECK (version >= 0),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT chk_expense_refunds_operation_identity
        CHECK ((recorded_by IS NULL) = (operation_id IS NULL)),
    CONSTRAINT uq_expense_refunds_group_requester_operation
        UNIQUE (group_id, recorded_by, operation_id)
);

CREATE INDEX idx_expense_refunds_expense ON expense_refunds (expense_id);
CREATE INDEX idx_expense_refunds_group ON expense_refunds (group_id);
CREATE INDEX idx_expense_refunds_received_by ON expense_refunds (received_by);
CREATE INDEX idx_expense_refunds_recorded_by ON expense_refunds (recorded_by);

-- Allocation is stored, not re-derived: a later expense correction must not restate a refund
-- that already happened. Shares sum exactly to amount_cents, payer absorbing the remainder.
CREATE TABLE expense_refund_participants (
    id             UUID   PRIMARY KEY,
    refund_id      UUID   NOT NULL REFERENCES expense_refunds (id) ON DELETE CASCADE,
    participant_id UUID   NOT NULL REFERENCES participants (id),
    share_cents    BIGINT NOT NULL CHECK (share_cents >= 0),
    CONSTRAINT uq_expense_refund_participants_refund_participant
        UNIQUE (refund_id, participant_id)
);

CREATE INDEX idx_expense_refund_participants_participant
    ON expense_refund_participants (participant_id);

-- The same mechanism as expense_revisions, generalised over the two new resources rather than
-- copied twice. Exactly one of the two references is set, and both cascade with their resource.
CREATE TABLE ledger_revisions (
    id              UUID        PRIMARY KEY,
    payment_id      UUID        REFERENCES payments (id) ON DELETE CASCADE,
    refund_id       UUID        REFERENCES expense_refunds (id) ON DELETE CASCADE,
    version         BIGINT      NOT NULL CHECK (version >= 0),
    action          VARCHAR(16) NOT NULL
        CHECK (action IN ('IMPORTED', 'CREATED', 'CORRECTED', 'DELETED', 'RESTORED')),
    actor           JSONB,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    operation_id    UUID,
    before_snapshot JSONB,
    after_snapshot  JSONB       NOT NULL,
    CONSTRAINT chk_ledger_revisions_subject
        CHECK ((payment_id IS NULL) <> (refund_id IS NULL))
);

CREATE UNIQUE INDEX uq_ledger_revisions_payment_version
    ON ledger_revisions (payment_id, version) WHERE payment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_ledger_revisions_refund_version
    ON ledger_revisions (refund_id, version) WHERE refund_id IS NOT NULL;

-- An imported baseline preserves known money, people and dates. The recorder of a pre-V9
-- payment is genuinely unknown, so actor stays null rather than being invented.
INSERT INTO ledger_revisions (id, payment_id, version, action, actor, after_snapshot)
SELECT gen_random_uuid(), p.id, p.version, 'IMPORTED',
       CASE WHEN p.recorded_by IS NULL THEN NULL ELSE jsonb_build_object(
           'id', recorder.id, 'email', recorder.email, 'displayName', recorder.display_name) END,
       jsonb_build_object(
           'id', p.id, 'amountMinor', p.amount_cents, 'currency', p.currency,
           'amountCents', CASE WHEN p.currency = 'USD' THEN p.amount_cents ELSE NULL END,
           'paidOn', p.paid_on, 'method', p.method, 'note', p.note,
           'createdAt', p.created_at, 'version', p.version, 'deletedAt', p.deleted_at,
           'payer', jsonb_build_object('id', payer.id, 'displayName', payer.display_name),
           'payee', jsonb_build_object('id', payee.id, 'displayName', payee.display_name),
           'recordedBy', CASE WHEN p.recorded_by IS NULL THEN NULL ELSE jsonb_build_object(
               'id', recorder.id, 'email', recorder.email,
               'displayName', recorder.display_name) END)
FROM payments p
         JOIN participants payer ON payer.id = p.payer_id
         JOIN participants payee ON payee.id = p.payee_id
         LEFT JOIN users recorder ON recorder.id = p.recorded_by;
