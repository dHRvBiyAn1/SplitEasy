-- Drain old application instances. Guest/alias data requires a compatible forward fix.
CREATE TABLE participants (
 id UUID PRIMARY KEY, display_name TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL,
 scope_group_id UUID REFERENCES groups(id) ON DELETE CASCADE
);
CREATE INDEX idx_participants_scope_group_id ON participants(scope_group_id);
CREATE TABLE participant_account_links (
 participant_id UUID PRIMARY KEY REFERENCES participants(id) ON DELETE CASCADE,
 user_id UUID NOT NULL REFERENCES users(id), linked_at TIMESTAMPTZ NOT NULL,
 link_kind VARCHAR(24) NOT NULL CHECK(link_kind IN ('PRIMARY','VERIFIED_EMAIL_CLAIM')),
 invitation_id UUID UNIQUE,
 CHECK(link_kind <> 'PRIMARY' OR participant_id=user_id)
);
CREATE INDEX idx_participant_links_user ON participant_account_links(user_id);
CREATE TABLE group_participants (
 group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
 participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
 active BOOLEAN NOT NULL, added_at TIMESTAMPTZ NOT NULL,
 PRIMARY KEY(group_id,participant_id)
);
CREATE INDEX idx_group_participants_participant_id ON group_participants(participant_id);
INSERT INTO participants(id,display_name,created_at) SELECT id,display_name,created_at FROM users;
INSERT INTO participant_account_links(participant_id,user_id,linked_at,link_kind)
 SELECT id,id,created_at,'PRIMARY' FROM users;
INSERT INTO group_participants SELECT group_id,user_id,true,joined_at FROM group_memberships;
INSERT INTO group_participants(group_id,participant_id,active,added_at)
 SELECT group_id,participant_id,false,CURRENT_TIMESTAMP FROM (
 SELECT group_id,paid_by AS participant_id FROM expenses
 UNION SELECT e.group_id,p.user_id FROM expense_participants p JOIN expenses e ON e.id=p.expense_id
 UNION SELECT group_id,payer_id FROM payments
 UNION SELECT group_id,payee_id FROM payments
 ) facts ON CONFLICT DO NOTHING;
ALTER TABLE expenses DROP CONSTRAINT expenses_paid_by_fkey;
ALTER TABLE expenses ADD CONSTRAINT expenses_paid_by_fkey FOREIGN KEY(paid_by) REFERENCES participants(id);
ALTER TABLE expense_participants DROP CONSTRAINT expense_participants_user_id_fkey;
ALTER TABLE expense_participants ADD CONSTRAINT expense_participants_user_id_fkey FOREIGN KEY(user_id) REFERENCES participants(id);
ALTER TABLE payments DROP CONSTRAINT payments_payer_id_fkey, DROP CONSTRAINT payments_payee_id_fkey;
ALTER TABLE payments ADD CONSTRAINT payments_payer_id_fkey FOREIGN KEY(payer_id) REFERENCES participants(id),
 ADD CONSTRAINT payments_payee_id_fkey FOREIGN KEY(payee_id) REFERENCES participants(id);
CREATE TABLE group_invitations (
 id UUID PRIMARY KEY, group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
 kind VARCHAR(8) NOT NULL CHECK(kind IN ('EMAIL','LINK')),
 participant_id UUID REFERENCES participants(id) ON DELETE CASCADE, target_email TEXT,
 token_hash CHAR(64) NOT NULL UNIQUE, created_by UUID NOT NULL REFERENCES users(id),
 created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ, accepted_at TIMESTAMPTZ, accepted_by UUID REFERENCES users(id),
 CHECK((accepted_at IS NULL)=(accepted_by IS NULL)),
 CHECK((kind='EMAIL' AND target_email IS NOT NULL) OR (kind='LINK' AND target_email IS NULL AND participant_id IS NULL))
);
CREATE INDEX idx_group_invitations_group ON group_invitations(group_id,created_at DESC);
CREATE INDEX idx_group_invitations_participant_id ON group_invitations(participant_id);
CREATE INDEX idx_group_invitations_created_by ON group_invitations(created_by);
CREATE INDEX idx_group_invitations_accepted_by ON group_invitations(accepted_by);
ALTER TABLE participant_account_links ADD CONSTRAINT fk_participant_claim_invitation
 FOREIGN KEY(invitation_id) REFERENCES group_invitations(id) ON DELETE SET NULL;
