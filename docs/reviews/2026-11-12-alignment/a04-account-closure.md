# A04 — account closure with financial preservation (V27)

Plan: `docs/superpowers/plans/2026-11-12-alignment-wave8.md` (A04). Owner decisions applied: blocked
while any balance is non-zero, shown as "Closed account", email freed.

## What shipped

- `POST /api/me/closure {currentPassword}` → 204. New `AccountClosureController` +
  `AccountClosureService` (all closure SQL lives there; `GroupService` and the revision writers are
  untouched). Same sensitive-command rate limit as password change.
- Under the user-row lock and the id-ordered locks of the user's group rows: refuse 409 naming
  groups / "Personal expenses with X" when any balance (per currency, via `BalanceService`) for a
  participant owned by the account is non-zero; transfer `created_by` to the longest-standing other
  member, or soft-delete a group with no one else; delete memberships; deactivate and rename the
  account's participants; revoke the account's unaccepted invitations; scrub the user row (name
  "Closed account", tombstone email `closed+<id>@closed.invalid`, phone empty, unusable password
  hash, `closed_at`, credential version bumped); revoke all sessions; delete challenges.
- History anonymisation (own SQL, one pass): `expense_revisions`, `ledger_revisions`,
  `activity_events`, `create_operations` rows mentioning the user id have actor/participant objects
  rewritten to "Closed account" + tombstone email, and any string equal to the old email replaced.
  Amounts and IDs unchanged.
- `AuthService.login` and `AuthSessionService.refresh` also check `closed_at` explicitly.
- Frontend: profile "Close account" section (`close-account.component.ts`), `AuthService.closeAccount`,
  login notice for `?reason=account-closed`.
- OpenAPI: `/api/me/closure` + `CloseAccountRequest` below the a04 anchors.

## Review follow-ups (integrator, PR #125)

- Bookmarks of a closed account are never deleted. `PersonalLedgerService` now refuses every
  expense/payment write path (create, update, delete, restore, record, correct, undo, restore) with
  403 "This account is closed"; reads still work and show "Closed account". Red first: with the
  bookmark-delete SQL removed, a bookmark opened a ledger with the closed account (201).
- Closure now locks each non-deleted group/personal ledger the user belongs to through
  `GroupWriteGuard.lockForMemberAllowingArchived`, ascending UUID, before the balance check.
  Real-PostgreSQL race test: an expense write holding the group lock makes closure wait; after it
  commits closure returns 409 naming the group. (Not red-first: the earlier SQL `FOR UPDATE` in id
  order already satisfied it; the test locks the behaviour in.)

## Red → green

- Backend `AccountClosureIntegrationTest` (3 tests): red against the missing endpoint, green after
  implementation. Covers: unsettled → 409 naming the group and nothing changes (login, `closed_at`);
  wrong password → 400, unchanged; settled closure → old access token 401, refresh cookie 401,
  old-email login 401, pre-closure reset token 400, new reset request sends no mail; creator
  transfer, lone group soft-deleted, memberships gone; other members read group / balances /
  obligations / expenses / payments / expense history / account history / activity / dashboard /
  friends / replayed keyed receipt with no old email and no old display name anywhere in the
  collected response bodies; a bookmark without a ledger gets 403 opening a personal expense;
  re-registering the old email creates a new account with no groups or friends.
- Frontend `close-account.spec.ts` (3 tests): red on the missing component, then green: no request
  without password + confirmation; 409 message shown and session kept; success clears the session
  and lands on `/login?reason=account-closed`.

## Verification

See the PR Testing section for final suite counts.

## Agents.md delta

Progress Log:

- [x] Alignment A04 account closure (2026-11-12) — V27 `users.closed_at`; `POST /api/me/closure`
  (current password, 409 while any balance is non-zero) transfers/soft-deletes created groups, ends
  memberships, scrubs the account to "Closed account" with a freed tombstone email, revokes every
  session and anonymises stored revisions/events/receipts without changing amounts or IDs. Closed
  accounts cannot log in, refresh or reset. Irreversible; no purge. Drain old backends before V27.

Conventions / Data Model:

- `users.closed_at` (V27) marks a closed account; its email is `closed+<id>@closed.invalid`, name
  "Closed account". `AccountClosureService` owns all closure SQL.
- `PersonalLedgerService` write paths refuse a closed friend account with 403; bookmarks stay.
