# Multi-payer contributions

An expense records two independent sets of money in its own currency:

- **contributions** — who actually paid, summing to the amount;
- **shares** (`participants`) — who owes, summing to the same amount.

Design and rollout: [E03 plan](../superpowers/plans/2026-11-11-alignment-e03.md).

## Wire contract

`CreateExpenseRequest.contributions` is optional (`[{userId, amountMinor}]`). Omitted or null keeps
the single-payer contract: `paidByUserId` paid everything. A present list must be nonempty,
duplicate-free, contain `paidByUserId`, name only active financial participants, hold
`1..1e12` minor units each, and sum exactly to the expense amount; otherwise 400 before any row
changes. `paidByUserId` stays required and means the **primary payer** — a contributor and the
rounding recipient.

`ExpenseResponse.contributions` is `[{user, amountMinor, currency}]`, ordered by display name, on
every current read. A replayed pre-E03 create receipt keeps its original JSON, so it may not have
the field. `ExpenseSummary.payerCount` is the number of contributors and `viewerDeltaMinor` is the
viewer's contributed minus owed minor units for that expense.

**Full-replace PUT without `contributions` on an expense that has several payers returns 409** —
an older client must reload in a current app rather than silently collapse the payers. PUT with
contributions replaces both sides in one transaction.

Keyed create retries compare contributions as part of the intent, ordered by `userId`; the same
`operationId` with different contributions is 409.

## Attribution

Net balances read contributions instead of the primary payer. Direct (unsimplified) pairwise
obligations are attributed inside each multi-payer expense: a person's own share is covered by
their own contribution first, then debtors owe creditors in proportion (`BigInteger` floor), and
the remaining minor units are filled debtors-by-UUID to creditors primary-payer-first so every
debtor row and creditor column is exact. Single-payer expenses keep the existing aggregate pair
query and are unchanged. Simplified mode is unchanged.

## Migration and rollout

V23 creates `expense_contributions` and backfills one row per existing expense, deleted ones
included, from `paid_by`/`amount_cents`. Drain old backends before deploying: an old binary writes
no contribution row, and readers now take paid amounts from contributions. Rollback before any
multi-payer write only needs the old binary; after one, an old binary treats the primary payer as
having paid everything (still zero-sum, wrong attribution), so roll back with a forward fix.

Known follow-ups: the dashboard activity feed still values an item's `viewerDeltaMinor` from the
primary payer (H03), refunds credit the primary payer (B01), and the CSV export switches to
contributions when H05 lands.
