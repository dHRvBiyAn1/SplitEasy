# G02.2 personal expenses with a friend (2026-11-11)

Plan: [G02.2](../../superpowers/plans/2026-11-10-alignment-g02-2-personal-ledger.md). Branch `feat/alignment-g02-2-personal-ledger`, PR to `dev`.

## What shipped

- **V21**: `groups.kind` (`GROUP`/`PERSONAL`, default `GROUP`) and `personal_ledgers` (one hidden ledger per unordered account pair, creator-independent key, unique `group_id`). Additive; no existing row changes.
- **Backend**: `PersonalLedgerService/Controller/Repository` expose `/api/friends/{friendId}/expenses` (list, create, detail, PUT, DELETE, deleted, restore). They call `ExpenseService` unchanged, so split intent, versions, V12 keyed replay, V16 history/recovery and V15 currency partitioning all apply. The ledger is created in the same transaction as the first expense, with `INSERT ... ON CONFLICT DO NOTHING` arbitration for first-write races.
- **Authorization**: the requester's saved-friend bookmark only authorizes creating the ledger. Reading or changing it needs ledger membership. Third parties, unsaved targets, self/owned aliases and unclaimed guests are refused (403 / 400 with claim-first guidance).
- **Exclusion**: `PersonalLedgerRouteGuard` (an MVC interceptor on `/api/groups/**`) returns 404 for a member of a personal ledger on every group route, and non-members pass through to the existing 403. `GroupService.listMyGroups` and the dashboard group cards/settle-up rows/activity omit ledgers; dashboard totals and People still include the money. `FriendDetail.personalBalances` separates the scopes and `sharedGroupCount` counts real groups only. Saving a friend from a ledger is 404.
- **Frontend**: `/friends/:id` renders `app-personal-expenses` (list, add/edit with fixed pair, equal/exact splits, delete at the seen version, keyed uncertain-write review). The component is hosted outside the detail `@if`, so a refresh does not destroy it or its notice, and it reloads on dashboard refreshes. The remove-friend text says the ledger stays with both people.

## Deviation from the plan

The plan named `MembershipGuard`/`GroupWriteGuard` for refusing ledgers on group routes. A single route interceptor covers every `/api/groups/{groupId}/...` route in one place and the integration test asserts all of them, so the guards stay untouched. Friend save from a ledger is refused in `FriendService`. The interceptor takes the repository through `ObjectProvider` because `@WebMvcTest` slices scan it.

## Evidence

- Backend `./mvnw -B spotless:check verify`: 384 tests, 0 failures (379 before). `PersonalLedgerIntegrationTest` has 5 HTTP/PostgreSQL cases: friend detail in both accounts, ledger never a group (15 group routes x 2 members -> 404, outsider 403, absent from `/groups` and dashboard groups while totals/people include it), authorization matrix, ledger contract (currency, intent, replay, versions, delete/restore), concurrent first-write claim.
- Frontend (Node 22.17.1): 394 tests in 56 files, 0 failures (386 before); production build 554.95 kB (budget warning pre-existing, was 554.90). Prettier is clean for `src`; `angular.json` and two tsconfig files fail the whole-tree check on `dev` and were not touched.
- OpenAPI additions sit after the existing friends paths and next to `FriendDetail`; official-schema validation was not re-run here.
- Not verified: packaged populated V20→V21 rehearsal, native browser pass at 1280/390, deployment scripts, independent review axes.

## Rollout / rollback

Drain old backends before personal writes: an old binary would list a ledger as an ordinary group. V21 follows V19/V20. Rollback keeps the schema; once personal expenses exist, use a forward fix. No closing/retention path for a ledger yet (A04).

## Agents.md delta

- Data Model: `groups.kind` (V21) and `personal_ledgers(account_low, account_high, group_id)`. A PERSONAL group is a hidden pair ledger, never a user-visible group; the name is historical. Stored this way so every financial path is reused (rejected: nullable `expenses.group_id`, separate tables).
- API: `/friends/{id}/expenses` family (needs `X-Participant-Version: 1`); `FriendDetail.personalBalances`.
- Conventions: group routes refuse personal ledgers with 404 via `PersonalLedgerRouteGuard`; `/groups` and dashboard cards exclude them while totals/People include them; saved-friend removal never revokes ledger access.
- Frontend: `personal-expenses.component` is hosted outside the friend detail `@if` and reloads on `dashboard.refreshes`.
- Progress Log: `[x] Alignment G02.2 personal expenses (2026-11-11)` — hidden pair ledger (V21) + friend-scoped expense CRUD + UI; backend 384, frontend 394/56.

## Review follow-up (PR #115, two Codex P2)

- Edit lost split intent: only EQUAL/UNEQUAL are editable, so PERCENTAGE/WEIGHTS/ADJUSTMENTS entries now open as UNEQUAL exact amounts from stored shares (legacy UNEQUAL without `splits` too), with a visible note; saving keeps those amounts.
- Recovery: "Recently deleted" list with Restore at the seen `expectedVersion`; a 409/5xx stays visible with a reload action; success refreshes balances and the open list. Rendered red→green for both.
