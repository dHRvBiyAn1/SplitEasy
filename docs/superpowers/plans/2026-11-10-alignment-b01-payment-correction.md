# B01 — payment detail, correction, date/method/note and refunds

Contract: `2026-10-19-splitwise-alignment.md`, M2. Depends on H01 (audit/version/soft delete),
E02 (keyed creates, opaque versions) and C01 (denominated ledger), all merged in `dev`.

A settle-up row today is an amount, a pair and a server timestamp. B01 gives it the detail a
real ledger entry needs, makes it correctable without rewriting history, and adds the one money
movement the ledger could not express at all: money coming back out of an expense.

## Scope

In: payment financial date / method / note / recorder on reads, payment correction (full-replace
PUT), recoverable delete and restore, attributable payment revisions, expense refunds with a
reference and derived allocation, a payment-history screen with those entry points.

Out: cross-group settlement (B02), global activity feed and pagination (H03/H05), group lifecycle
(G03), editing or restoring a refund, refunds in the dashboard activity feed, any real money
movement. Recording a settlement here remains a *record* of a transfer the people made
themselves — nothing in SplitEasy moves funds, and the UI says so where a method is chosen.

## Schema decision

One additive migration, **V20**. G03 is taking V19 in a parallel session; V20 is the next free
number and must not collide. Nothing is edited in an applied migration and no existing positive
amount CHECK is relaxed.

`payments` gains:

- `paid_on DATE NOT NULL` — the user-chosen financial date, backfilled from `created_at` at UTC
  for existing rows, exactly as expenses separate `spent_on` from `created_at`.
- `method VARCHAR(16) NOT NULL DEFAULT 'OTHER'`, CHECK over `CASH / BANK_TRANSFER / CARD /
  ONLINE / OTHER`. An enum column, not free text, so the history screen can group and the wire
  contract stays closed.
- `note VARCHAR(280)` — free text, nullable.
- `version BIGINT NOT NULL DEFAULT 0` — opaque optimistic precondition, mirroring `expenses.version`.
- `deleted_at TIMESTAMPTZ` — recoverable delete marker, mirroring `expenses.deleted_at`, with a
  partial index `(group_id, deleted_at DESC, id)` for the recovery list.

`recorded_by` already exists (V9) and already carries the real recorder independently of the
payer. V9 paired it with `operation_id`; B01 keeps that pairing, so a legacy unkeyed payment still
has a null recorder and the history reports it as imported, never invented.

Ordering: the payment list moves from `created_at DESC` to `paid_on DESC, created_at DESC`, the
same rule expenses use, with a new composite `idx_payments_group_paid_on`. The existing
`idx_payments_group_created_at` stays — the dashboard activity feed still merges by `created_at`.

`ledger_revisions` is new and holds revisions for both new resources with real foreign keys:

```
payment_id UUID REFERENCES payments(id) ON DELETE CASCADE
refund_id  UUID REFERENCES expense_refunds(id) ON DELETE CASCADE
CHECK ((payment_id IS NULL) <> (refund_id IS NULL))
version, action, actor JSONB, recorded_at, operation_id, before_snapshot, after_snapshot
```

It is the same mechanism as `expense_revisions` — same columns, same JSONB snapshots, same
imported baseline rule — generalised over two tables rather than copied twice. Payment baselines
are inserted as `IMPORTED` with a null actor and the original `created_at` inside the snapshot.
`expense_revisions` is left untouched; expenses keep their own table and reader.

Refunds are two tables shaped like `expenses` / `expense_participants`:

```
expense_refunds(id, expense_id→expenses CASCADE, group_id→groups CASCADE, currency,
                amount_cents BIGINT CHECK (> 0), received_by→participants, refunded_on DATE,
                note, recorded_by→users, operation_id, created_at, version, deleted_at)
  UNIQUE (group_id, recorded_by, operation_id)
expense_refund_participants(id, refund_id→expense_refunds CASCADE, participant_id→participants,
                            share_cents BIGINT CHECK (>= 0))
  UNIQUE (refund_id, participant_id)
```

The amount is positive and validated like any other amount; the reversal is expressed by the
reader subtracting these rows, not by a negative number anywhere in the database.

## Refund economics

A refund is money coming back out of one expense: the merchant returns part of what was spent.
Its effect is exactly the reverse of that expense's own allocation, so the refund carries no split
input of its own — a split input would let a user invent an allocation the expense never had.

At creation the service reads the expense's stored `share_cents`, allocates the refund amount
pro rata by `floor(amount * share / total)`, and gives the remainder to the receiving payer under
the existing `SplitStrategy.absorbRemainder` rule, so allocations sum exactly to the amount. The
allocation is **stored**, not re-derived on read: a later expense correction must not silently
restate a refund that already happened.

`received_by` is the expense's payer at the moment of the refund, stored explicitly rather than
followed through later edits.

Balance effect, per currency: `paid[received_by] −= amount` and `owed[p] −= allocation[p]`. Because
the allocations sum to the amount, group balances still sum to zero, and in pairwise terms each
debt `p → received_by` falls by `allocation[p]`. Three aggregate queries feed this — received,
allocated, and by pair — merged into the existing `BalanceService` and obligation maps as negative
terms. No per-refund loop, and no existing query changes shape.

Validation: positive, within the catalogue exponent, currency equal to the expense's immutable
currency, and the sum of live refunds never exceeding the expense amount. The expense must be
active, and the receiving payer and every allocated participant must be active financial
participants — the same historical-participant guard an expense edit already applies.

## Access rules

Every new route is group-scoped and calls `MembershipGuard.requireMember` (reads) or
`GroupWriteGuard.lockForMember` (writes) **before** loading by id, so a non-member gets 403 on a
nonexistent payment, refund or group. Writes serialize on the group row under READ COMMITTED with
the existing financial writers; reads join the REPEATABLE READ ledger snapshot.

Group authorization stays flat, as it is for expenses: any active member may correct, delete or
restore a payment, or record a refund. The recorder is captured on every revision, so a flat rule
still produces an attributable record.

## Wire contract

All under `/api/groups/{g}`, bearer-protected.

| Method | Path | Purpose |
|---|---|---|
| GET | `/payments` | history, newest financial date first, excluding deleted |
| POST | `/payments` | record (unchanged keyed create, now accepting date/method/note) |
| GET | `/payments/{p}` | active detail with version |
| PUT | `/payments/{p}` | correction — full replace at `expectedVersion` |
| DELETE | `/payments/{p}` | recoverable delete at `expectedVersion`, 204 |
| POST | `/payments/{p}/restore` | restore at `expectedVersion` |
| GET | `/payments/deleted` | recovery list |
| GET | `/payments/{p}/history` | attributable revisions |
| GET/POST | `/expenses/{e}/refunds` | list / record a refund, keyed |
| DELETE | `/expenses/{e}/refunds/{r}` | recoverable delete at `expectedVersion`, 204 |

`PaymentResponse` gains `paidOn`, `method`, `note`, `recordedBy` (nullable `UserSummary`),
`version` and `deletedAt`. Existing fields and the USD `amountCents` alias are unchanged, so an
old client reading payments keeps working.

Correction rules, mirroring the expense editor: `expectedVersion` is **required** on PUT, DELETE
and restore — payments have never had versions, so there is no legacy writer to grandfather and
no finite cutoff is needed. A stale version is 409 before any mutation. Currency is immutable; a
PUT that changes it is 409 with upgrade guidance. Payer and payee must differ and both must be
active participants. Every accepted write advances the version by one and writes exactly one
revision in the same transaction.

Operation identity: PUT, DELETE, restore and refund POST accept an optional `operationId`. For the
refund it is the V9-style unique `(group_id, recorded_by, operation_id)` claim with native
`ON CONFLICT DO NOTHING` and an identical-intent replay, so a duplicate retry returns the original
201 and a changed intent is 409. For the correction routes the operation id is recorded on the
revision and, because the write is already guarded by `expectedVersion`, a replayed correction
finds the version already advanced and returns 409 rather than applying twice. That is the
property the acceptance asks for: retries and stale edits never double-reverse.

## Frontend

A new route `groups/:id/payments` and a standalone `PaymentHistoryComponent` replace the minimal
list inside the settle-up modal, which keeps only its "record a payment" job and links to the
screen. The screen lists each entry with its financial date, method, note, counterparties,
amount via `app-money`, and who recorded it; it offers correct, undo and restore per row, a
deleted section, a per-row revision list, and a refund entry point from an expense row.

It reuses what exists rather than adding a second way to do any of it: `.dc-*` primitives and
`_tokens.scss`, `app-money` for amounts, `ModalShellComponent`/`ModalService` for the correction
and refund dialogs, `DashboardService.refresh()` after every mutation, `takeUntil(session.changes)`
plus a load generation for ownership, and the existing pending-modal close guard. Errors stay
visible and actionable; a 409 keeps the form open with its values and tells the user to reload,
exactly as the expense editor does.

## Acceptance

Regression-first, one behaviour at a time, at the HTTP seam against real PostgreSQL and at the
rendered-component seam for Angular. Races use real commits and PostgreSQL blocking.

1. Correcting the payer, and separately the amount, moves balances to the corrected position once
   and leaves one `CORRECTED` revision with both snapshots.
2. Delete removes the entry from balances, history and the activity feed, keeps its revisions, and
   restore puts it back once at the right amount.
3. A partial refund reduces the payer's paid and each participant's owed by the stored allocation,
   per currency, keeps balances summing to zero, and a second identical keyed retry returns the
   original receipt without reversing twice.
4. A stale `expectedVersion` on correct, delete or restore is 409 with the ledger unchanged.
5. A refund larger than the expense's unrefunded remainder, in the wrong currency, or on a deleted
   expense is rejected with the ledger unchanged.
6. A non-member gets 403, not 404, on every new route including nonexistent ids.
7. Two currencies in one group never mix in any new aggregate.

## Rollout

V20 is additive and backfills `paid_on` from `created_at`; existing amounts, currencies, pairs,
recorders and timestamps are untouched.

Old backends must be drained before V20 payments are written, for the same reason H01 gave for
expenses: an old binary's payment aggregates have no `deleted_at` filter and would count a deleted
payment, and it cannot see refunds at all, so its balances would be wrong in both directions.

Keep the schema on rollback. Rolling the application back after corrections or refunds exist loses
the corrections from view and silently restores the pre-refund balances; recovery is a forward fix,
never an old binary. Dropping the columns or tables destroys audit history that exists nowhere else.

Order: V19 (G03) and V20 may apply in either order — they touch different tables — but neither may
be applied out of sequence with its own predecessor.
