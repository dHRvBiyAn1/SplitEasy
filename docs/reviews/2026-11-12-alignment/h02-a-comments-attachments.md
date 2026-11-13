# H02-A — expense comments and attachments (backend)

Branch `feat/alignment-h02-a-comments-attachments`, migration V29. Backend only.

## What shipped

- V29 `expense_comments` and `attachments` (bytea), soft delete, cascade with the expense, FK columns indexed.
- `comment/*` and `attachment/*` packages: list / create / delete-own for both, attachment download.
  Group routes bind `{groupId}` (so `PersonalLedgerRouteGuard` returns 404 for a personal ledger's id);
  `/api/friends/{friendId}/expenses/{expenseId}/…` mirrors them by authorizing through
  `PersonalLedgerService.get` and then using the returned ledger group id.
- `ActivityRecorder.comment` emits `COMMENT_CREATED`, delivered under the `expenses` preference.
- OpenAPI paths/schemas under the `h02` anchors.

## Trust boundary

- Stored type = detected from magic bytes (JPEG, PNG, WebP `RIFF….WEBP`, PDF `%PDF-`). The client
  `Content-Type` is ignored: a PNG header on HTML/EXE bytes is 400, and HTML claimed over JPEG bytes
  is stored and served as `image/jpeg`. Bare `RIFF` that is not WebP (WAV) is 400.
- 5 MB cap → 413 (`ResponseStatusException`); exactly 5 MB accepted. 10 live attachments per expense → 409.
  The DB also checks type and size.
- File name is display-only: directory part, control and quote characters stripped, max 255.
- Download: stored type, `Content-Disposition: attachment`, `X-Content-Type-Options: nosniff`,
  `Cache-Control: private, no-store`. List/metadata responses never carry bytes.
- Member before resource (403 before 404); writes go through `GroupWriteGuard.lockForMember`
  (archived → 409, deleted group → 404, lock serializes the attachment count).

## Red → green

`CommentAttachmentIntegrationTest` (8 cases) written first against V29 only: 8/8 failed (no routes).
After the implementation: 8/8 pass. Cases: comment auth/author/soft-delete/validation, event once and
preference-gated, archived group, detected type + download headers + sanitized name, all four types,
spoofed/empty/truncated/missing-part uploads, 5 MB cap + 10-attachment limit, personal-ledger mirror
and hidden ledger group.

## Not verified

- The 413 from the servlet container's own multipart limit (`AttachmentConfig`, 5 MB file / 6 MB request)
  is not exercised: MockMvc does not enforce it. The service-level 413 is tested.
- Writes on a personal ledger do not check `requireOpenAccount` (private to `PersonalLedgerService`);
  a closed friend's ledger therefore still accepts comments. Comments are not financial entries.
- Frontend suites were not run: this package changes no frontend file.

## Agents.md delta

- **API table:** add `GET/POST /groups/{g}/expenses/{e}/comments`, `DELETE …/comments/{c}`,
  `GET/POST …/attachments`, `GET/DELETE …/attachments/{a}`, and the `/friends/{id}/expenses/{e}/…` mirrors.
- **Backend convention:** Attachments live in PostgreSQL (V29, bytea, 5 MB, ≤10 live per expense), type
  verified by magic bytes and stored as detected, name display-only, downloads `attachment` + `nosniff` +
  `private, no-store`. Comments/attachments are member-only, write through `GroupWriteGuard.lockForMember`
  (archived 409), author/uploader-only soft delete. Personal routes authorize via `PersonalLedgerService.get`.
  `AttachmentConfig` overrides Boot's 1 MB multipart default.
- **Activity:** `COMMENT_CREATED` (via `ActivityRecorder.comment`) follows the `expenses` preference.
- **Data model:** `expense_comments`, `attachments` (V29). Drain old backends before V29; forward-fix only.
- **Progress log:** Alignment H02-A comments + attachments backend — V29, 8 integration cases; backend 451 (443 before).
