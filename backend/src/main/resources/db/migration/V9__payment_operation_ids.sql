-- Null identities preserve legacy additive payments; keyed operations belong to the JWT requester.
ALTER TABLE payments
    ADD COLUMN recorded_by UUID REFERENCES users (id),
    ADD COLUMN operation_id UUID,
    ADD CONSTRAINT chk_payments_operation_identity
        CHECK ((recorded_by IS NULL) = (operation_id IS NULL)),
    ADD CONSTRAINT uq_payments_group_requester_operation
        UNIQUE (group_id, recorded_by, operation_id);

CREATE INDEX idx_payments_recorded_by ON payments (recorded_by);
