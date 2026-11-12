# B02 — atomic cross-group friend settlement (12 November 2026)

Branch `feat/alignment-b02-cross-group-settlement`, migration V26, design per
`docs/superpowers/plans/2026-11-12-alignment-wave8.md`.

## What shipped

- `GET /api/friends/{id}/settlement-preview?currency=` and `POST /api/friends/{id}/settlements`
  (new `com.spliteasy.settlement` package). One ordinary `Payment` per shared scope (each group and
  the personal ledger) via `PaymentService`, in one transaction; `GroupWriteGuard` locks every scope in
  ascending UUID order, the allocations are recomputed under the locks and must match the request
  exactly, otherwise 409 with nothing written. Archived scopes are listed in `skipped`.
- V26 `settlement_batches` (claim + intent + response, unique `(actor_id, operation_id)`, native
  `ON CONFLICT DO NOTHING`) and `settlement_allocations`. Exact replay returns the stored 201 body;
  a changed request under the same `operationId` is 409.
- Friend page "Settle everything": per open currency, review of each scope with direction, skipped
  groups and net; Confirm keeps one operation ID through uncertain errors; 409 re-previews with a new ID.
- OpenAPI paths/schemas below the b02 anchors (74 paths, 0 missing refs).

## Red → green

- Backend `FriendSettlementIntegrationTest` (5 cases): red = 5/5 failing with 404 before the
  controller existed; green after implementation. Covers settle-to-zero across two groups plus the
  personal ledger, exact/changed replay, stale preview after a raced expense (nothing written),
  archived scope skipped, currencies never mixed, third user and non-friend 403, concurrent batches
  finish without deadlock (one 201, one 409).
- Frontend `friend-settle-all.spec.ts` (3 cases): red = 3/3 failing (no button) before the component.

## Decisions / limits

- Non-friend and third-user requests are 403 (the plan's test list), not the 404 `GET /friends/{id}` gives.
- Shared scopes are found by group membership of the friend; a friend who only exists as a claimed
  guest alias or former member in a group is not included.
- The retained operation ID is in memory only (like the payment modal before E02 drafts); a reload
  between an uncertain error and retry starts a fresh review.

## Agents.md delta

Progress Log line:

- [x] Alignment B02 cross-group friend settlement (2026-11-12) — `GET /friends/{id}/settlement-preview` and `POST /friends/{id}/settlements` settle one currency across every shared non-archived group and the personal ledger atomically: scopes locked in UUID order via `GroupWriteGuard`, allocations recomputed and required to match exactly (else 409, nothing written), one ordinary payment per scope through `PaymentService`. V26 `settlement_batches`/`settlement_allocations` give keyed exact replay. Friend page "Settle everything" review with retained operation ID. Additive schema; no cross-currency netting or partial settlement.

Data Model line: **settlement_batches / settlement_allocations** (V26) — `settlement_batches(id, actor_id→users, friend_account_id→users, currency, operation_id, intent jsonb, response jsonb, created_at)` unique `(actor_id, operation_id)`; `settlement_allocations(batch_id→batches CASCADE, payment_id→payments CASCADE, group_id→groups CASCADE, amount_cents)`.

API table row: GET `/friends/{id}/settlement-preview`, POST `/friends/{id}/settlements` | per-currency cross-scope preview / atomic keyed commit.
