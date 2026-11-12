-- Recurring expense schedules and their idempotent occurrence ledger (H04). Drain old backends
-- first; keep this schema on rollback (forward fix only). The occurrence key is what stops two
-- runners or a restart from creating one expense twice.
CREATE TABLE recurring_schedules (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES users(id),
    template JSONB NOT NULL,
    frequency VARCHAR(10) NOT NULL CHECK (frequency IN ('DAILY','WEEKLY','MONTHLY')),
    anchor_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    next_date DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','NEEDS_REVIEW','CANCELLED')),
    review_reason VARCHAR(400),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    cancelled_at TIMESTAMPTZ,
    operation_id UUID
);
CREATE INDEX idx_recurring_schedules_group_created ON recurring_schedules(group_id, created_at DESC);
CREATE INDEX idx_recurring_schedules_created_by ON recurring_schedules(created_by);
-- A lost create response retries with the same operation ID and must not add a second schedule.
CREATE UNIQUE INDEX uq_recurring_schedules_operation
    ON recurring_schedules(created_by, group_id, operation_id) WHERE operation_id IS NOT NULL;
CREATE INDEX idx_recurring_schedules_due ON recurring_schedules(next_date) WHERE status = 'ACTIVE';

CREATE TABLE recurring_occurrences (
    schedule_id UUID NOT NULL REFERENCES recurring_schedules(id) ON DELETE CASCADE,
    occurrence_date DATE NOT NULL,
    expense_id UUID REFERENCES expenses(id) ON DELETE SET NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('CREATED','SKIPPED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (schedule_id, occurrence_date)
);
CREATE INDEX idx_recurring_occurrences_expense ON recurring_occurrences(expense_id);
