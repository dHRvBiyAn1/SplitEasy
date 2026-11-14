-- Expense comments and attachments (H02-A). Attachment bytes live in PostgreSQL (bytea, capped at
-- 5 MB by the service). Soft delete only; rows cascade with their expense. Drain old backends
-- first; keep this schema on rollback (forward fix only).
CREATE TABLE expense_comments (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id),
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_expense_comments_expense_created ON expense_comments(expense_id, created_at);
CREATE INDEX idx_expense_comments_author ON expense_comments(author_id);

CREATE TABLE attachments (
    id UUID PRIMARY KEY,
    expense_id UUID NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
    uploaded_by UUID NOT NULL REFERENCES users(id),
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL
        CHECK (content_type IN ('image/jpeg','image/png','image/webp','application/pdf')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256 CHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_attachments_expense_created ON attachments(expense_id, created_at);
CREATE INDEX idx_attachments_uploaded_by ON attachments(uploaded_by);
