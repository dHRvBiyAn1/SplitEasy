# #61: payment retry semantics

Root cause: every authorized payment POST constructed a fresh entity; neither
request nor database had an operation identity. The settlement modal was the
only frontend caller. Backend direct callers (including balance snapshot tests)
used the three-argument DTO constructor, retained for compatibility.

Implementation: optional UUID operation identity scoped to JWT requester/group;
V9 unique constraint and paired nullable columns; membership before replay,
all payer/payee/amount fields compared, 409 for conflicting valid payloads.
Native PostgreSQL `ON CONFLICT DO NOTHING` arbitrates racing inserts and a fresh
READ COMMITTED lookup returns the original row. No second ledger mutation,
exception recovery inside an aborted transaction, dependency, or balance state.
The Angular payment service retains one pending identity through uncertain
errors/modal reopen, clears it on success, changes it for different submitted
intent, and resets/cancels it on account changes. A prior-session observable
cannot send with a new session. See [contract](../../api/payment-retries.md).

Testing evidence (throwaway local data only):

- Frontend baseline regression: four HTTP contract/retry/session tests failed
  before the service change. Full Angular/Vitest suite then passed 114 tests.
- Full source Prettier check and production Angular build passed.
- Real Chromium loaded the production Angular build on localhost with intercepted
  synthetic API responses. Four rendered form submissions verified identical
  group/payer/payee/1250-cent payload and UUID after network abort, HTTP 503,
  close/reopen, then a new UUID after success. Browser/API fixtures contained
  invented accounts only; this check did not use a live backend.
- Sequential baseline regressions exposed new response IDs on replay and HTTP 201
  for a reused key with a changed amount. The corrected concurrent fixture
  delegates real Spring Data proxy calls and coordinates both API transactions
  after replay lookup and before insertion. With the service temporarily reverted
  to baseline, both race cases failed behaviorally (2 failures, 0 errors).
  Restoring the fix passed all 19 payment API cases: identical sequential and
  concurrent outcomes, one persisted history row and one balance delta,
  independently changed payer/payee/amount conflicts, distinct IDs, requester and
  group scope, member-only access, and legacy additive requests.
- Full backend `spotless:check verify` passed 147 tests with no failures/errors;
  Flyway applied V9 successfully on PostgreSQL 16 and JPA schema validation passed.
- Independent source review found no blockers; `git diff --check` passed.

Commands (from each respective backend/frontend directory):

```sh
JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify
npm test -- --watch=false
npx prettier --check "src/**/*.{ts,html,scss,json}"
npm run build -- --configuration production
```

The browser rehearsal used local Chromium with Playwright; logs and its temporary
synthetic fixture are retained outside the checkout under `/workspace/issue-61-*`.
It complements the real PostgreSQL/security/controller/service API tests above.

Limits: legacy omitted/null operation IDs remain additive. Pending client IDs
are in memory, so browser reload/cross-tab cannot recover them; consult history
before recreating an uncertain payment. Changing submitted intent after an
uncertain response may create a separate payment while the first already
committed. No automatic retries, persistent queue, or historical deduplication.
No merge or deployment performed.
