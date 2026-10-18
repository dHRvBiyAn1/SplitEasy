# Issue #52 — validate serialized expense splits

Verified locally on 2026-10-18 against dev baseline `f68d818`.

The percentage gate rounded the sum of raw inputs, but serialization rounded each
input separately. Negative entries contributed to the live total and gate, then
were filtered out of the request. Exact splits shared that filtering mismatch.
The create/edit modal is the only caller constructing these custom split inputs;
`ExpenseService` passes the request through for both POST and PUT. Existing edits
of percentage expenses still prefill lossless exact cents.

The modal now caches one outgoing split list using the existing cents/basis-point
converters. It rejects negative and nonfinite raw values, and unsafe integer
results, before omitting zero values. The running total, gate, and request reuse
that list. Each share rounds to the nearest hundredth using the existing
`Math.round(value * 100)` conversion; a linked form hint explains this, and
`step="any"` allows the stated precision policy through native form validation.
No conversion helper, backend, schema, or dependency changed.

## Regression evidence

Focused component/HTTP tests in
`frontend/src/app/modals/new-expense-modal.component.spec.ts` first reproduced
17 failures (11 existing/new cases passed), then passed all 28 cases. Coverage
includes reported thirds; negative/tiny-negative, NaN/infinite/unsafe values;
zero omission; exact totals; valid two-decimal and rounded percentages; outgoing
integer/sum invariants; and existing percentage-to-exact PUT prefill.

## Testing

- `cd frontend && npm test -- --watch=false --include=src/app/modals/new-expense-modal.component.spec.ts`: 28 passed.
- `cd frontend && npm test -- --watch=false`: 133 passed across 23 files.
- `cd frontend && npx prettier --check "src/**/*.{ts,html,scss,json}"`: passed.
- `cd frontend && npm run build -- --configuration production`: passed without warnings.
- `cd backend && JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify`: 138 tests passed, no failures/errors/skips.
- `git diff --check`: passed.
- Independent review and parent review: no substantive blockers.

Local headless Chromium at `http://127.0.0.1:4252`, with synthetic account/group
fixtures and intercepted API calls, exercised the actual form and submit path:

1. Percentages `33.335/33.335/33.33` displayed `100.01% / 100%` and disabled Save.
2. `-5/105/0` disabled Save for percentages and exact splits.
3. `33.334/33.334/33.335` displayed `100.00% / 100%`, passed native
   `form.checkValidity()`, and submitted percentage integers `[3333,3333,3334]`.
4. Exact `100/0/0.001` displayed `$100.00 / $100.00`, passed native form validity,
   and submitted one positive share of `10000` cents.

Both intercepted request sums were independently asserted against the required
integer total. Browser API responses were mocked; this does not claim a live
frontend-to-backend save or production validation. Backend tests use disposable
local Testcontainers databases. No merge or deployment.
