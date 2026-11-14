# Alignment Wave 7 — package plans (12 November 2026)

Integrator-written plans for the three Wave 7 packages. Ownership, anchors and migration numbers
follow the [parallel batch plan](2026-11-11-alignment-parallel-batches.md); this file settles the
design so workers implement rather than decide. Base: `dev` after Wave 6 (#114–#117). A worker
that finds a question this plan does not answer stops and reports instead of guessing.

## Ownership this wave (supersedes the Wave 7 table where they differ)

| Package | Owns | Must not touch |
| --- | --- | --- |
| **E03-B** multi-payer editor | `modals/new-expense-modal.*`, `expenses/*` models/service, expense detail/receipt rendering in `groups/group-detail.*`, `core/pending-writes*` only for the expense draft shape | All backend; `friends/*`; settle-up modal; shell |
| **G02.3** friend debts and payments | `FriendService`, `FriendController`, `dto/friend/*`, `PersonalLedger*` (service, controller, repository), new `PersonalPayment*` wrappers, frontend `friends/*`, `modals/settle-up-modal.*` | `PaymentService`/`PaymentRepository` (call only), `DashboardService`, expense editor, revision writers |
| **H03** durable activity + in-app notifications | V25, `ExpenseHistory`, `LedgerHistory`, `GroupService` (event calls only), new `activity/*` and `notification/*` (backend packages and frontend folders), shell layout (bell), `profile/*` notification section | `DashboardService` (feed stays as is this wave), expense/payment/refund services, `friends/*`, expense editor |

Change from the batch plan: H03 does **not** take over the dashboard activity feed this wave.
E03-A just fixed its viewer delta; moving it onto events is a later, separate change.

## E03-B — multi-payer editor (frontend only, no migration)

Backend contract is merged (E03-A, `docs/api/multi-payer-contributions.md`).

- Editor: under "Paid by", a "Multiple people" toggle. Off = today's single payer select; the
  request omits `contributions` (byte-identical to today). On = one amount row per chosen payer
  with a live remainder ("12.00 left to assign" / "3.00 over"), reusing the existing minor-unit
  parsing/formatting helpers and `app-money`. `paidByUserId` = the primary payer (first row,
  user-selectable), who absorbs rounding server-side.
- Gate save on the actual serialized contributions: nonempty, unique, each > 0, sum ===
  `amountMinor`, includes `paidByUserId`. Re-validate after an amount change, like custom splits.
- Edit: an expense whose response has more than one contribution opens in multi mode with its
  stored amounts. **Any PUT of an expense that had >1 contribution always sends
  `contributions`**, including when the user switches back to one payer (send
  `[{payer, amount}]`); otherwise the server returns 409.
- Retry drafts: the pending expense draft (`PendingWritesService` kind `expense`) carries
  contributions; changed contributions count as changed intent.
- Detail/receipt in group detail: list each contributor with `app-money`; the summary row shows
  "Ann and 2 others paid" when the summary's payer count is > 1.
- Accessibility: each amount input is labelled with the contributor's full name + email, like
  split inputs; the remainder is announced politely.
- Tests (rendered Vitest, red first): single-payer POST unchanged; multi POST payload; remainder
  gate incl. amount change; edit multi → keeps contributions; edit multi → single sends one
  contribution; 409 keeps the form open; draft restore with contributions; receipt rendering.
- Out of scope: the personal-expense (friends) editor — G02.3 only preserves contributions there.

## G02.3 — friend debts and payments (V24 only if truly needed; expected none)

A settlement between two friends outside any group is a normal `Payment` in their hidden
personal ledger (V21), so every B01 rule (date, method, note, version, undo/restore, revisions,
keyed retries, currency) applies unchanged.

- Backend routes under `/api/friends/{friendId}/payments`, mirroring group payments and wrapping
  `PaymentService` through the existing `PersonalLedgerService` authorization (same 403/400
  rules; a POST may open the ledger exactly like a personal expense create): `GET` list, `POST`
  record (payer and payee must be exactly the pair, either direction), `GET/PUT/DELETE
  /{paymentId}`, `GET /deleted`, `POST /{paymentId}/restore`, `GET /{paymentId}/history`. Same
  `X-Participant-Version: 1` requirement as personal expenses.
- Friend detail: keep `sharedGroups` and `personalBalances`; nothing nets currencies or scopes
  together. Totals stay per currency across groups + personal ledger.
- Frontend friend page: a "Balances" breakdown — one row per group (links to that group's settle
  up) and one "Personal" row — plus "Settle up" for the personal balance, implemented as a friend
  scope of the existing settle-up modal (prefilled payer/payee/amount/currency from the personal
  balance, editable for partial payments). Cross-group netting is B02, not this package: copy says
  group debts are settled in their group.
- Personal payment history on the friend page: date, method, note, amount, recorder, undo,
  restore of recently undone, revisions — reuse the payments screen's row/correction patterns.
- Fix carried from Wave 6: the personal-expense edit form must send back the stored
  `contributions` unchanged when an expense has more than one (multi-payer personal expenses can
  exist via the API); otherwise saving returns 409.
- Tests (red first): HTTP — pair-only payer/payee (third account rejected), third user cannot
  list or record (403), keyed retry replays, undo/restore at expected version, personal + group
  totals reconcile per currency on friend detail and dashboard; rendered — breakdown, settle-up
  prefill, partial payment, 409 stale undo, multi-payer personal edit keeps contributions.

## H03 — durable activity and in-app notifications (V25)

Committed events, written in the same transaction as the financial revision, back the new
activity screen. In-app delivery only: production SMTP is deferred, so email delivery and push
are out of scope (no unused email columns either).

Schema (V25):

- `activity_events(id uuid pk, seq bigint generated always as identity unique, group_id uuid not
  null → groups on delete cascade, kind varchar(40) not null, subject_id uuid, actor jsonb null,
  summary jsonb not null, occurred_at timestamptz not null default clock_timestamp())`, index
  `idx_activity_events_group_seq (group_id, seq desc)`. `actor` is the captured recorder (same
  JSON as revisions); `summary` is a small snapshot: description, amountMinor, currency, payer /
  payee participant summaries, version.
- `notifications(id uuid pk, recipient_id → users cascade, event_id → activity_events cascade,
  read_at timestamptz null, created_at …)`, unique `uq_notifications_event_recipient (event_id,
  recipient_id)`, index `idx_notifications_recipient_read_created (recipient_id, read_at,
  created_at desc)`.
- `notification_preferences(user_id pk → users cascade, expenses bool default true, payments bool
  default true, group_changes bool default true)`; absent row = all true.
- No backfill: events start at V25. The activity screen says so when the list ends.

Write path (same transaction, so a rolled-back write leaves no event or notification):

- New `ActivityRecorder` (package `activity`). `ExpenseHistory.record` and `LedgerHistory.record`
  call it after inserting the revision — every expense/payment/refund create/correct/delete/
  restore already passes through them, so no financial service changes.
- `GroupService` calls it for archive, unarchive, delete, restore, member added, removed, left.
- Recipients: current active group members except the actor, filtered by preferences
  (`expenses` / `payments` incl. refunds / `group_changes`). For a personal ledger: the other
  friend. Insert with `ON CONFLICT DO NOTHING` on the unique pair.
- Keyed replays that return a stored receipt write nothing (they write no revision either).

Read path:

- `GET /api/activity?groupId=&cursor=&limit=` — default 50, max 100, keyset on `seq desc`.
  No `groupId`: all non-deleted groups the viewer currently belongs to (personal ledgers included,
  with null group name). With `groupId`: `requireMember` first (403 before 404), deleted group
  404, personal-ledger `groupId` 404 (same rule as history; `PersonalLedgerRepository` read-only).
- `GET /api/notifications?cursor=&unreadOnly=` (own rows only), `GET
  /api/notifications/unread-count`, `POST /api/notifications/{id}/read` (404 for someone else's),
  `POST /api/notifications/read-all`.
- `GET/PUT /api/me/notification-preferences`.
- Former members keep their past notifications but get no new ones; activity access follows
  current membership.

Frontend:

- Route `activity` (anchor `h03`): account activity with optional group filter, paging, retry;
  edited/deleted/restored entries show their verb and the recorder (distinct from payer).
- Shell header bell with unread count, refreshed through the existing freshness pattern
  (navigation/visible-focus revalidation; no polling timer, no websockets); a panel or page
  listing notifications with mark-read / mark-all-read.
- Profile: notification preferences section (three switches, server-owned, session-owned like the
  rest of profile).

Tests (red first): HTTP/PostgreSQL — expense create/edit/delete/restore, payment, refund and
group lifecycle each produce exactly one event; a rejected or rolled-back write produces none;
keyed replay produces none; recipients exclude the actor and respect preferences; non-member 403
and personal-ledger 404 on activity; another user's notification 404; read-all. Rendered —
activity paging/filter/retry, bell count and mark-read, preferences save/failure.

Rollout: drain old backends before V25 (they would write financial rows without events, so the
activity screen would silently miss them). Rollback keeps the schema; forward fix only.
