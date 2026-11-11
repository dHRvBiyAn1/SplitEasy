-- A personal ledger is a hidden pair-scoped group, so every existing financial path (balances,
-- projections, recovery, retry claims, currency partitions, the group write lock) applies unchanged.
-- Drain old backends before personal writes: they would list a personal ledger as an ordinary group.
-- Keep this schema on rollback and use a forward fix once personal expenses exist.
ALTER TABLE groups ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'GROUP' CHECK (kind IN ('GROUP','PERSONAL'));

-- One ledger per unordered account pair; UUID ordering makes the key creator-independent.
CREATE TABLE personal_ledgers (
    account_low UUID NOT NULL REFERENCES users(id),
    account_high UUID NOT NULL REFERENCES users(id),
    group_id UUID NOT NULL UNIQUE REFERENCES groups(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (account_low, account_high),
    CHECK (account_low < account_high)
);
CREATE INDEX idx_personal_ledgers_account_high ON personal_ledgers(account_high);
CREATE INDEX idx_groups_kind_personal ON groups(id) WHERE kind = 'PERSONAL';
