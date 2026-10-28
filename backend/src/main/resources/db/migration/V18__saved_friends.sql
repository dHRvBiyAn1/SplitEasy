CREATE TABLE friends (
    owner_id UUID NOT NULL REFERENCES users(id),
    friend_account_id UUID NOT NULL REFERENCES users(id),
    display_name_snapshot TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, friend_account_id),
    CHECK (owner_id <> friend_account_id)
);
CREATE INDEX idx_friends_friend_account_id ON friends(friend_account_id);
CREATE INDEX idx_friends_owner_sort ON friends(owner_id, display_name_snapshot, created_at, friend_account_id);
