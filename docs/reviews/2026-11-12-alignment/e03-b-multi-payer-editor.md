# E03-B multi-payer expense editor (frontend only, no migration)

Contract: `docs/api/multi-payer-contributions.md`. Design: Wave 7 plan, E03-B.

## Change
- `expenses/expense.models.ts`: `Contribution`, `ContributionInput`, `ExpenseResponse.contributions`, `ExpenseSummary.payerCount`, `CreateExpenseRequest.contributions`.
- `modals/new-expense-modal.component.*`: "Multiple people paid" toggle, one labelled amount input per member, live polite remainder ("3.00 left to assign" / "1.00 over"), save gate (nonempty, each > 0, sum === amount, includes primary payer; re-evaluated on amount change). Off = request omits `contributions`. An expense that had >1 contribution always PUTs `contributions` (one row when switched back to a single payer). Edit opens in multi mode with stored amounts; uncertain create drafts carry and restore contributions (draft shape is the request, so `core/pending-writes*` needed no change).
- `groups/group-detail.component.*`: summary "Ann and 2 others paid $10.00" when `payerCount > 1`; receipt lists each contributor.

## Red → green (rendered Vitest)
- `modals/multi-payer-expense.spec.ts` (10 tests): first run failed to compile (no `contributions` type); after adding the types, 8 failed / 2 passed (the two single-payer tests, as intended); after implementation 10/10.
- `groups/multi-payer-receipt.spec.ts` (1 test): failed on the summary text, passes after the change.

## Verification
- Frontend (Node 22.17.1): 410 tests / 59 files pass (399/57 before); Prettier check on `src` clean; production build OK (initial 557.98 kB).
- Backend (unchanged): `./mvnw -B spotless:check verify` BUILD SUCCESS, 405 tests.
- Not verified: real browser, real backend round trip.

## Agents.md delta
- Progress Log: `- [x] Alignment E03-B multi-payer editor (2026-11-12) — "Multiple people paid" toggle in the expense editor with per-payer labelled amounts, live remainder and a save gate on the serialized contributions; edit of a multi-payer expense always PUTs contributions (one row when collapsed to a single payer); retry drafts carry contributions; summary "Ann and 2 others paid" and per-contributor receipt lines. Frontend only, no migration. Frontend 410/59.`
- Frontend Conventions: `Multi-payer editor: single-payer requests omit contributions; any PUT of an expense that loaded with >1 contribution sends contributions (else 409). Personal-expense (friends) editor is out of scope.`
