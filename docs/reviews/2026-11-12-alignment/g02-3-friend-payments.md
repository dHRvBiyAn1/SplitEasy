# G02.3 — friend debts and personal payments (12 November 2026)

No migration (V24 unused): a settlement between two friends is a normal `Payment` in the pair's hidden V21 ledger.

## What changed

- Backend: `PersonalPaymentController` (`/api/friends/{friendId}/payments`: list, POST, GET/PUT/DELETE `/{id}`, `/deleted`, `/{id}/restore`, `/{id}/history`). `PersonalLedgerService` wraps `PaymentService` (unchanged) behind the same ledger authorization as personal expenses (403 for unsaved/third accounts, POST may open the ledger, `X-Participant-Version: 1`). Record and correct require payer/payee to be exactly you and the friend, either direction (400 otherwise, before any write).
- Frontend: `PersonalPaymentsComponent` on the friend page (per-group rows linking to the group, a "Personal" row per currency with "Settle up <CUR>", payment history with date/method/note/recorder, Undo at the seen version, Recently undone + Restore, Revisions). The settle-up modal gets a friend scope: a prefill carrying `friendId` (and no `groupId`) records through `FriendService.recordPersonalPayment` (keyed, same `payment` pending-write kind, scope `personal:<friendId>`). Copy says group debts are settled in their group; currencies and scopes are never netted.
- Wave 6 fix: the personal-expense edit sends stored `contributions` unchanged when an expense has more than one (typed `Contribution`/`ContributionInput` from the E03-B expense models).
- OpenAPI: paths added under the g02-3 anchor; no new schemas.

## Red → green

- `PersonalPaymentIntegrationTest` (4 cases): all 404 before the controller existed; green after. Covers either-direction pair payments reconciling on friend detail, third-account payer/payee 400, third user 403 on list/record, keyed replay (same id; changed amount 409), undo/restore at expected version (stale 409), correction keeps the pair.
- Frontend: multi-payer edit PUT lacked `contributions` (red) → sent (green). `personal-payments.spec.ts` 4 cases red (no component) → green. Settle-up modal friend-scope case added.

## Agents.md delta

- API table: add `GET/POST /friends/{id}/payments`, `GET/PUT/DELETE /friends/{id}/payments/{p}`, `.../payments/deleted`, `.../{p}/restore`, `.../{p}/history` — personal settlements in the pair ledger; payer/payee must be the pair.
- Conventions: friend settle-up reuses the settle-up modal with a optional `friendId` on the modal's `SettlePrefill` (`groupId` only ever holds a real group id); pending-write scope key `friend:<friendId>`. Personal payments reuse `PaymentService` via `PersonalLedgerService` (no schema change).
- Progress Log: `Alignment G02.3 friend debts and personal payments (2026-11-12) — /friends/{id}/payments wraps PaymentService through the pair ledger; friend page balances breakdown, personal settle-up, payment history/undo/restore/revisions; personal-expense edit keeps multi-payer contributions. No migration (V24 unused).`

## Not verified

Post-review gate on the branch up to date with dev (includes E03-B #118): backend 409 tests (spotless:check verify); frontend 420 tests in 61 files (after the two Codex fixes: Back on a friend settle-up closes the modal; a slower revisions response is cancelled), Prettier clean, production build 560.16 kB; backend unchanged since its 409-test run. New `modals/friend-settlement.spec.ts` (real services, HTTP): a friend settle-up POSTs `/api/friends/pat/payments` and no `/groups` URL; a group settle-up still POSTs `/api/groups/g1/payments`. Note: importing `PAYMENT_METHOD_LABEL` from the friend component broke the existing payments spec in the full bundle (undefined at runtime), so the method label is formatted locally.

No real-browser run and no populated-DB rehearsal. Personal payment correction (PUT) has HTTP coverage but no UI.
