-- Durable activity events and in-app notifications, written in the same transaction as the
-- financial revision. No backfill: events start here. Drain old backends before V25 writes (they
-- would write financial rows without events). Keep this schema on rollback; forward fix only.
CREATE TABLE activity_events (
    id UUID PRIMARY KEY,
    seq BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    kind VARCHAR(40) NOT NULL,
    subject_id UUID,
    actor JSONB,
    summary JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_activity_events_group_seq ON activity_events(group_id, seq DESC);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recipient_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_id UUID NOT NULL REFERENCES activity_events(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_notifications_event_recipient UNIQUE (event_id, recipient_id)
);
CREATE INDEX idx_notifications_recipient_read_created
    ON notifications(recipient_id, read_at, created_at DESC);

CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    expenses BOOLEAN NOT NULL DEFAULT TRUE,
    payments BOOLEAN NOT NULL DEFAULT TRUE,
    group_changes BOOLEAN NOT NULL DEFAULT TRUE
);
