# Alignment Wave 8 — package plans (12 November 2026)

Integrator-written plans for B02, A04 and H04. Rules, anchors and migration numbers follow the
[parallel batch plan](2026-11-11-alignment-parallel-batches.md); this file settles the design so
workers implement rather than decide. Base: `dev` after Wave 7 (#118–#121). A worker that finds a
question this plan does not answer stops and reports instead of guessing.

Owner decisions (12 November 2026), binding for A04: closure is **blocked while any balance is
non-zero**; a closed account is shown as **"Closed account"** everywhere; its **email is freed** and
a later registration is a brand-new empty account.

## Ownership this wave (supersedes the batch plan's Wave 8 table)

| Package | Owns | Must not touch |
| --- | --- | --- |
| **B02** cross-group friend settlement (V26) | New `settlement/*` backend package, frontend settle-all UI in `friends/*` | `PaymentService`/`PaymentRepository` (call only), `GroupWriteGuard` (call only), `DashboardService`, profile/auth |
| **A04** account closure (V27) | `AuthService`, `AuthSessionService`, `AccountLifecycleService`, `SecurityConfig`, `User` entity, `ProfileService`, new `AccountClosureService`, frontend `profile/*`, `auth/*` | `GroupService` (do closure SQL in your own class), settlement, recurrence, revision writer classes (rewrite their tables with your own SQL) |
| **H04** recurrence + balance reminders (V28) | New `recurrence/*` and `reminder/*` backend packages, `ActivityRecorder` (add a reminder method only), frontend new `recurring/*`, repeat control in `modals/new-expense-modal.*`, a Remind action in `groups/group-detail.*` | `ExpenseService` (call only), profile, friends UI, `DashboardService` |

Anchors `b02`, `a04`, `h04` are in `openapi.yaml` (paths and schemas) and `app.routes.ts`.

## B02 — atomic cross-group friend settlement (V26)

Settle **everything** two friends owe each other in **one currency**, across every shared group
and their personal ledger, in one transaction. Each scope gets its own ordinary `Payment` in the
direction of that scope's obligation, so every group's ledger explains itself — no synthetic
friend-only payment and no cross-currency netting. Partial cross-group settlement is out of scope
(per-scope settle up already exists).

- `GET /api/friends/{friendId}/settlement-preview?currency=` → `{currency, allocations: [{scopeId,
  groupName (null for personal), payerId, payeeId, amountMinor}], skipped: [...], netMinor}` from
  the canonical pair obligation of each non-deleted, non-archived scope where both are financial
  participants (same `PairwiseBalanceService` projection the friend detail uses). Archived scopes
  are listed in `skipped` with a reason; they cannot be settled here.
- `POST /api/friends/{friendId}/settlements` with `{currency, allocations (exactly as previewed),
  operationId (required UUID), paidOn, method, note}`: authorize like the friend detail, lock every
  scope with `GroupWriteGuard` **in ascending UUID order**, recompute the allocations under the
  locks, and require an exact match — any difference (raced expense, new scope, settled elsewhere,
  archived meanwhile) is **409 with nothing written**. Then record one payment per allocation by
  calling `PaymentService` (so revisions and H03 activity events happen as usual) inside the same
  transaction, and write the batch.
- V26: `settlement_batches(id, actor_id→users, friend_account_id→users, currency, operation_id,
  intent jsonb, response jsonb, created_at)` unique `(actor_id, operation_id)`;
  `settlement_allocations(batch_id→batches cascade, payment_id→payments, group_id→groups,
  amount_cents)`. Claim with `INSERT … ON CONFLICT DO NOTHING`; an exact replay returns the stored
  response, a changed intent with the same `operationId` is 409.
- Frontend (friend page): per currency with a non-zero total, "Settle all in USD" opens a review
  of the previewed allocations (each group and Personal, with direction and amount, plus the net);
  confirm commits with a retained operation ID through uncertain errors (same pattern as the
  payment operation identity); 409 shows "balances changed — review again" and re-previews.
- Tests (red first): opposing obligations in two groups settle to zero in both with two payments;
  stale preview after a raced expense is 409 and writes nothing; one archived scope is skipped, not
  settled; non-friend / third user 403; exact replay identical; changed replay 409; currencies never
  mix; two concurrent batches over the same two groups finish without deadlock.

## A04 — account closure with financial preservation (V27)

- `POST /api/me/closure` `{currentPassword}` (reauthentication, BCrypt check, same rate-limit
  posture as sensitive account commands). Under the user-row lock:
  1. **Refuse (409) while any balance is non-zero** in any currency in any non-deleted group or
     personal ledger the user is a financial participant of; the message names the groups/friends.
  2. For every group the user created that still has other members, pass `created_by` to the
     longest-standing remaining member (earliest `joined_at`, then UUID). A group with no other
     members is soft-deleted (it is settled by step 1).
  3. Remove the user's group memberships (personal ledgers included). Financial participant rows,
     expenses, shares, contributions, payments, refunds, revisions and events stay untouched.
  4. Scrub the account: `display_name='Closed account'`, email → unique tombstone
     `closed+<userId>@closed.invalid` (frees the address), phone empty, password hash replaced by
     an unusable value, `closed_at=now()`, credential version bumped; revoke every session and
     refresh credential; invalidate all account challenges.
  5. Anonymize stored history for this account with your own SQL in `AccountClosureService`: in
     `expense_revisions`, `ledger_revisions`, `activity_events` and E02 `create_operations`, rewrite
     `actor` objects whose id is this user, and participant summaries inside stored snapshots /
     summaries / responses that carry this user's id, to the "Closed account" name with the email
     removed. Amounts and IDs never change.
- V27: `users.closed_at timestamptz null`. No purge: rows are never deleted (retention policy:
  financial history is kept for the other participants; irreversible personal-data purge stays
  disabled until retention requirements are stated).
- Closed accounts cannot log in, refresh, reset a password or verify (the tombstone address cannot
  receive mail and login by the old address finds nothing). Registering the old address creates a
  new user with no link to old participants. Saved-friend bookmarks pointing at a closed account
  show "Closed account" and cannot open new personal expenses or payments (403).
- Frontend `profile/*`: a "Close account" section — explains the policy, links to the CSV export
  (H05) first, shows the blocking balances from a 409, requires the current password and an
  explicit confirmation; success clears the session and lands on login with a neutral notice.
- Tests (red first): unsettled → 409 naming scopes and nothing changes; settled closure → login,
  refresh and old sessions fail; creator transfer and lone-group soft delete; other members still
  read full balances/history with "Closed account" and no old email anywhere in API responses
  (including revisions, activity and replayed receipts); re-registration with the old email is a
  fresh account with no groups/debts; wrong password rejected with nothing changed.

## H04 — recurring expenses and balance reminders (V28)

Recurring expenses:

- V28: `recurring_schedules(id, group_id→groups cascade, created_by→users, template jsonb (a
  `CreateExpenseRequest`), frequency DAILY|WEEKLY|MONTHLY, anchor_date date, time_zone text (IANA),
  next_date date, version bigint, status ACTIVE|NEEDS_REVIEW|CANCELLED, review_reason, created_at,
  cancelled_at)` and `recurring_occurrences(schedule_id→cascade, occurrence_date, expense_id null,
  status CREATED|SKIPPED|FAILED, created_at)` with primary key `(schedule_id, occurrence_date)`.
- Calendar: occurrence dates are local dates in the schedule's zone; the expense's `spent_on` is
  the occurrence date. Weekly keeps the anchor weekday. **Month-end policy:** monthly keeps the
  anchor day-of-month, clamped to the month's last day (31 Jan → 28/29 Feb → 31 Mar, always from
  the anchor, never drifting).
- Runner: a `@Scheduled` job (enable scheduling once) every 15 minutes generates due occurrences.
  Per occurrence, in one transaction: insert the occurrence row `ON CONFLICT DO NOTHING` (zero rows
  → another worker/restart already did it, skip), then call `ExpenseService.createExpense` as the
  schedule creator and store `expense_id`. Two workers or a restart can never produce two expenses.
- Catch-up: missed occurrences up to **30 days** old are generated; older ones are recorded
  `SKIPPED` (explicit, never silent). At most 31 occurrences per schedule per run.
- Review halt: if the creator is no longer a member, the group is archived/deleted, or any template
  payer/participant is inactive, record the occurrence `FAILED`, set `NEEDS_REVIEW` with the reason,
  and stop that schedule until edited. No partial expense.
- API under `/api/groups/{groupId}/recurring` (member-only, 403 before 404; bind the variable as
  `{groupId}` so the personal-ledger route guard applies): list, create, get, `PUT` "change future
  occurrences" at `expectedVersion` (prior generated expenses untouched; resets NEEDS_REVIEW),
  `POST /{id}/cancel` (prevents future occurrences, deletes nothing).
- Frontend: a "Repeat" control (Never / Daily / Weekly / Monthly) in the new-expense modal for new
  group expenses, which creates a schedule instead of a one-off expense when set; a `recurring/*`
  list per group (route under the `h04` anchor) with edit-future, cancel and the review reason.

Balance reminders:

- `POST /api/groups/{groupId}/reminders` `{debtorId, currency}` by a member who is owed money by
  that debtor in that currency (canonical projection), delivered as an in-app notification through
  a new `ActivityRecorder.reminder(...)` (event kind `BALANCE_REMINDER`, recipient = the debtor
  only, gated by the debtor's `payments` preference). Dedup: at most one per (group, creditor,
  debtor, currency) per 24 hours (409 otherwise). No automatic periodic reminders and no email.
- Frontend: a "Remind" action next to an owed-to-me balance in group detail.

Tests (red first): two concurrent runners → one expense per occurrence; restart mid-catch-up; DST
transition date and 31st-of-month sequence; 45-day outage → 30 days created, older SKIPPED;
inactive participant → NEEDS_REVIEW, no expense; cancel stops future, keeps history; edit-future
leaves past expenses unchanged; reminder only by a creditor, dedup 24h, respects preference.

## Rollout

Drain old backends before V26–V28. Running the recurrence job on more than one instance is safe
(the occurrence key prevents duplicates), only wasteful. Rollback keeps the schema; forward fix
only. A04 closure is irreversible for the account owner (no reopen).
