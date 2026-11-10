-- Drain old backends before enabling recovery: old queries would count retained deleted rows.
-- Keep this schema on rollback; use a forward fix, never an old expense-reading binary.
ALTER TABLE expenses ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX idx_expenses_deleted ON expenses(group_id, deleted_at DESC, id) WHERE deleted_at IS NOT NULL;

CREATE TABLE expense_revisions (
    expense_id UUID NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    version BIGINT NOT NULL CHECK (version >= 0),
    action VARCHAR(16) NOT NULL CHECK (action IN ('IMPORTED','CREATED','REPLACED','DELETED','RESTORED')),
    actor JSONB,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    operation_id UUID,
    before_snapshot JSONB,
    after_snapshot JSONB NOT NULL,
    PRIMARY KEY (expense_id, version)
);

-- An imported baseline preserves known money and dates. Recorder/prior edits are unknown.
-- Match the detail reader's honest UNEQUAL fallback when a previous writer lost split intent.
INSERT INTO expense_revisions(expense_id,version,action,after_snapshot)
SELECT e.id,e.version,'IMPORTED',jsonb_build_object(
    'id',e.id,'groupId',e.group_id,'description',e.description,
    'amountMinor',e.amount_cents,'currency',e.currency,
    'amountCents',CASE WHEN e.currency='USD' THEN e.amount_cents ELSE NULL END,
    'splitType',CASE WHEN e.split_type<>'EQUAL' AND EXISTS (
        SELECT 1 FROM expense_participants p WHERE p.expense_id=e.id AND p.split_value IS NULL
    ) THEN 'UNEQUAL' ELSE e.split_type END,
    'category',e.category,'spentOn',e.spent_on,'createdAt',e.created_at,'version',e.version,
    'paidBy',jsonb_build_object('id',payer.id,'email',payer.email,'displayName',payer.display_name),
    'participants',COALESCE((SELECT jsonb_agg(jsonb_build_object(
        'user',jsonb_build_object('id',u.id,'email',u.email,'displayName',u.display_name),
        'shareMinor',p.share_cents,'currency',e.currency,
        'shareCents',CASE WHEN e.currency='USD' THEN p.share_cents ELSE NULL END
    ) ORDER BY u.display_name,u.id)
        FROM expense_participants p JOIN users u ON u.id=p.user_id WHERE p.expense_id=e.id),'[]'::jsonb),
    'participantUserIds',CASE WHEN e.split_type='EQUAL' THEN
        (SELECT jsonb_agg(p.user_id ORDER BY p.user_id) FROM expense_participants p WHERE p.expense_id=e.id)
        ELSE NULL END,
    'splits',CASE WHEN e.split_type='EQUAL' THEN NULL ELSE
        (SELECT jsonb_agg(jsonb_build_object('userId',p.user_id,'value',
            CASE WHEN EXISTS (SELECT 1 FROM expense_participants missing WHERE missing.expense_id=e.id AND missing.split_value IS NULL)
                THEN p.share_cents ELSE p.split_value END) ORDER BY p.user_id)
        FROM expense_participants p WHERE p.expense_id=e.id) END
)
FROM expenses e JOIN users payer ON payer.id=e.paid_by;
