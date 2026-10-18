# Issue #57: Greedy settlement wording

The settle-up and group-settings copy, model comments, README, and project guidance now describe simplified payments as greedy suggestions. No algorithm or payment amount behavior changed. The greedy service still guarantees zeroed member balances, no leftover cents, and at most N−1 transfers for N non-zero balances; existing coverage is in `DebtSimplificationServiceTest`.

The limit is visible for balances `[+8, +7, +6, -9, -8, -4]`: the greedy matcher emits five transfers, while four suffice: `-8→+8:8`, `-9→+7:7`, `-9→+6:2`, `-4→+6:4`. This counterexample was reproduced by the 2026-10-15 current-method probe recorded in the issue audit; it demonstrates why the UI no longer promises a global minimum. No exact optimizer or copy-only regression test was added.

Validation on 2026-10-18: backend `spotless:check verify` passed (138 tests); frontend `npm test -- --watch=false` passed (110 tests); Prettier check and production build passed.
