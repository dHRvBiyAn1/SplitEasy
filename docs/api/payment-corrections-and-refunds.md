# Payment corrections and expense refunds (B01)

B01 gives a recorded settlement the detail a ledger entry needs, makes it correctable without
rewriting history, and adds the one money movement the ledger could not express: money coming back
out of an expense. It does not move money, settle across groups (B02), or recover a deleted group.

## What a settlement now records

`PaymentResponse` keeps every field it had, including the USD-only `amountCents` alias, and adds
`paidOn`, `method`, `note`, `recordedBy`, `version` and `deletedAt`.

`paidOn` is the user-chosen financial date, separate from the server's `createdAt`, exactly as an
expense separates `spentOn`. Payments recorded before B01 are backfilled from `created_at` at UTC —
their real financial date is unknown and is not invented. The list is ordered by `paidOn` then
`createdAt`, newest first; the dashboard activity feed still orders by `createdAt`.

`method` is one of `CASH`, `BANK_TRANSFER`, `CARD`, `ONLINE`, `OTHER`, defaulting to `OTHER`. It is
descriptive metadata. **Recording, correcting or undoing a settlement here moves no real money** —
it records a transfer the two people made themselves, and the UI says so where a method is chosen.

`recordedBy` is the authenticated requester, independent of who paid. A payment recorded before the
recorder was captured reports `null` rather than a guess.

A settlement has **no viewer-scoped field**. Any member of the group reads exactly the document the
recorder received, `recordedBy` included — there is no redaction by viewer, which is what lets a
payee see a correction the other party made. Same for a refund.

Timestamps are **storage-faithful**: `createdAt` is truncated to microseconds before the row is
written, so a create receipt is byte-identical to a later read of the same entry. PostgreSQL
`timestamptz` keeps microseconds and *rounds*, so returning an unrounded `Instant.now()` would make
a receipt disagree with the row it describes by up to a microsecond — and it did, on a
nanosecond-resolution clock. This matters beyond cosmetics: E02 keyed create receipts and the
`ledger_revisions` snapshots store the response document, so a drifting timestamp would make a
stored receipt disagree with the live entry.

## Correction, undo and restore

| Route | Contract |
|---|---|
| `GET /payments/{p}` | active detail with its version |
| `PUT /payments/{p}` | full replace of payer, payee, amount, date, method and note |
| `DELETE /payments/{p}?expectedVersion=` | recoverable undo, 204 |
| `POST /payments/{p}/restore` | restore at the stated version |
| `GET /payments/deleted` | the group's recoverable settlements |
| `GET /payments/{p}/history` | attributable revisions, oldest first |

`expectedVersion` is **always required** on correct, undo and restore. Payments have never carried a
version, so unlike expenses there is no legacy writer to grandfather and no finite cutoff: a missing
version is 400 and a stale one is 409 before any mutation. That is what stops a retried correction
or undo from reversing twice.

Currency is immutable, like an expense's; a `PUT` that states a different one is 409. Payer and
payee must differ and both must be currently active financial participants, otherwise 409 asks for
an explicit re-add — the same historical-participant guard an expense edit applies. Writes serialize
on the group row with every other financial writer under READ COMMITTED.

Every accepted write advances the version by one and writes exactly one revision — `CREATED`,
`CORRECTED`, `DELETED` or `RESTORED` — in the same transaction, carrying the actor, the server time,
the operation id when one was sent, and complete before and after snapshots. The original is
retained; nothing is rewritten. Pre-B01 payments get one `IMPORTED` baseline whose actor is null.

An undone settlement keeps its row and its revisions and is excluded from every balance, obligation,
pairwise view, dashboard total, activity feed and departure check. `operationId` on correct, undo and
restore is attribution, not a replay contract — `expectedVersion` already makes a repeat 409.

## Refunds

A refund is money returned out of one expense. It is a separate, properly modelled entry with a
positive validated amount and a reference to the expense it refunds; no existing positive-amount
CHECK is relaxed anywhere, and no amount is stored negative.

| Route | Contract |
|---|---|
| `GET /expenses/{e}/refunds` | live refunds on the expense |
| `POST /expenses/{e}/refunds` | record one, keyed |
| `DELETE /expenses/{e}/refunds/{r}?expectedVersion=` | recoverable undo, 204 |
| `GET /expenses/{e}/refunds/{r}/history` | attributable revisions |

A refund **carries no split input of its own**: a split input would let a caller invent an
allocation the expense never had. Its economics are the exact reverse of that expense's own
allocation. The server distributes the amount pro rata over the expense's stored `share_cents` —
`floor(amount × share / total)`, computed with `BigInteger` — and gives the leftover minor units to
the receiving payer under the same remainder rule every split strategy uses, so the allocation sums
exactly to the refund. The allocation is **stored**, not re-derived on read: a later expense
correction must not silently restate a refund that already happened.

`receivedBy` is the expense's payer at the moment of the refund, stored explicitly rather than
followed through later edits.

Readers subtract refunds: `paid[receivedBy] −= amount` and `owed[p] −= allocation[p]`, per currency.
Because the allocations sum to the amount, group balances still sum to zero and each debt
`p → receivedBy` falls by `allocation[p]`. Balances, canonical and raw obligations, pairwise views,
People, settlement rows, departure checks and a group's gross spend all net refunds. Currencies
never mix: a refund is recorded in the expense's own immutable currency, and a different one is 400.

Validation: positive, within the catalogue bounds, and at most the expense's amount minus its live
refunds — more is 400 and states the remainder, with the ledger unchanged. The expense must be active
(404 otherwise) and its payer and every share participant currently active (409 with re-add
guidance).

`operationId` is a real keyed retry, claimed through the V12 `create_operations` table: an identical
repeat returns the original 201 without reversing twice, and a changed intent under the same key is
409.

**B01 has no refund edit or restore route.** Correct a wrong refund by undoing it and recording a
new one; the undone refund and its revisions are retained.

## Authorization

Every route is group-scoped and checks membership *before* loading by id, so a non-member gets 403
even for an id that does not exist — 403 before 404, uniformly. Group authorization stays flat, as
it is for expenses: any active member may correct, undo or restore a settlement, or record a refund.
The recorder is captured on every revision, so a flat rule still produces an attributable record.

## Rollout

V20 is additive and backfills `paid_on`; existing amounts, currencies, pairs, recorders and
timestamps are untouched. It also relaxes V9's paired-null operation check to "a keyed row is always
attributable", so the real recorder can be stored on an unkeyed payment.

**Drain old backends before V20 payments are written.** An old binary's payment aggregates have no
`deleted_at` filter, so it counts an undone settlement, and it cannot see refunds at all — its
balances would be wrong in both directions.

Keep the schema on rollback. Rolling the application back after corrections or refunds exist hides
the corrections and silently restores the pre-refund balances; recovery is a forward fix, never an
old payment-reading binary. Dropping the columns or tables destroys audit history that exists
nowhere else.
