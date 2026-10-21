# E02 verification evidence

Base: `e5b219773e54f890e56358f2a57de28f56d4bdff`; branch `bugfix/alignment-e02-retry-safe-writes`. Candidate verified on 21 October 2026. Public seams: authenticated MVC/HTTP with PostgreSQL; rendered Angular with controlled HTTP. Root owns independent Standards/Spec review, populated V11 upgrade, packaged HTTP/browser rehearsal and PR publication. No browser or deployment result is claimed here.

## Behavior

Group/expense creates accept scoped operation IDs and replay immutable original responses through native transactional PostgreSQL claims. Changed intent conflicts; each attempt reauthorizes current membership. Deleted resources cannot be resurrected. Expense list/detail expose opaque versions; expectedVersion guards PUT/DELETE under the group lock. Every accepted PUT advances the parent version, including unchanged replacements and child-only split-intent edits. Raw JPA and Spring-translated stale persistence failures return actionable 409 responses. Dirty-column group PATCH behavior is unchanged.

Account-owned, tab-isolated sessionStorage persists uncertain group/expense/payment intents before dispatch. Reload restores drafts without sending. Explicit identical retries reuse IDs; changed intent requires acknowledgement that the original may already have succeeded. Storage failure blocks dispatch. Eager initialization installs session cleanup before opening write dialogs; existing request cancellation and session ownership remain in force.

## Regression evidence

External logs are under `/workspace/issue-context/alignment-implementation/batch3/E02/`.

- Group lost-response retry: `02-group-green.log` follows the original duplicate-create red; same ID/timestamp replays, changed intent returns 409, list contains exactly one group.
- Expense edit/delete replay: `03-expense-red.log` → `04-expense-green.log`; immutable original response survives edit/hard deletion without recreating the expense.
- Stale separate GET→PUT/DELETE and rollout boundary: `05-stale-red.log` → `06-stale-green.log`, `07-cutoff-boundary.log`. The configured UTC instant is tested immediately before and exactly at the deadline; missing versions fail400 at the boundary and explicit fresh versions still work.
- Native raced identical/changed claims and normalized actor/kind/group scopes: `10-raced-claims.log`, `13-scopes-normalization.log`.
- Rendered reload restoration: group `08-group-reload-red.log` → `09-group-reload-green.log`; expense `11-expense-reload-red.log` → `12-expense-reload-green.log`; payment `17-payment-reload-red.log` → `18-payment-reload-green.log`.
- Child-only WEIGHTS 1/2→2/4 changed intent initially retained the same parent token: `23-child-intent-version-red.log` → `24-child-intent-version-green.log`. Parent force-increment after validation closes the stale-write gap; assertions treat versions as opaque increasing tokens.
- Existing independent stale-row HTTP test caught500 from the direct JPA force-increment call: `30-stale-row-red.log` → `33-stale-row-green.log`. The original 409/reload guidance and rollback/read assertions are retained; the handler now includes `jakarta.persistence.OptimisticLockException`.
- Exact versioned DELETE assertions retain all existing delayed-error ownership/refresh assertions: `31-delete-request-red.log` → `32-delete-request-green.log` (7 tests). Payment partial-amount assertion includes the persisted settlement draft: `34-payment-draft-red.log` → `36-payment-draft-green.log` (6 tests).
- Eager logout cleanup: `25-eager-logout-cleanup-red.log` → `26-eager-logout-cleanup-green.log`. Explicit separate-group acknowledgement: `28-explicit-separate-intent.log`. Added rendered separate-payment acknowledgement and storage-failure dispatch prevention: `37-payment-changed-intent.log`, `39-storage-failure.log`. Existing payment field-change coverage now checks the approved blocking contract for amount/group/payer/payee and preservation of the original identity (`40-payment-intent-fields.log`, 6 tests).

## Final checks

- Java21: `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` — **263 tests, zero failures/errors/skips**, packaged JAR; `38-backend-final-verify.log`.
- Node22: `cd frontend && npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false` — **246 tests across 34 files**, all pass; `42-frontend-full.log`.
- `cd frontend && npx prettier --check 'src/**/*.{ts,html,scss,json}'` — full-source pass; `45-frontend-prettier-final.log`.
- Node22: `cd frontend && npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build --configuration production` — pass; `46-frontend-production-build.log`.
- Official OpenAPI 3.1 schema, duplicate YAML keys and **148 internal refs** — pass using existing `F04/verify_contract.py` and schema 2025-09-15; `47-openapi-validation.log`.
- `git diff --check` — pass.

Fresh packaged artifact: external `backend-final-20261021.jar`, SHA256 `b05fac9e1d048b445fe5ab58f7f77b2fa5a467a04de9a5f8cdd34d6953a23cac`. The interrupted older `backend-checkpoint.jar` is preserved and does not contain the final child-only fix.

## Rollout and integration

V12 is additive; V1–V11 are untouched. Promote V12 before or together with A02 V13. Deploy the version-aware server coherently, then the client before the finite cutoff `2026-11-19T00:00:00Z` (`APP_EXPENSES_VERSION_REQUIRED_FROM`). Do not run mixed old/new backend writers. Old binaries bypass checks and version advancement; rollback after version-aware writes requires a forward fix or reviewed recovery. Never drop claims/reset versions. V11 WEIGHTS/ADJUSTMENTS retain their existing old-binary limit. See [write contract](../../api/retry-safe-writes.md).

H06 integration-sensitive `GroupDetailComponent` hunks only validate the loaded version before deletion and pass it to `deleteExpense`; preserve H06 refresh ownership. E02 adds the eager `PendingWritesService` app initializer. AuthService/SessionService internals are unchanged and remain A02-owned. Test fixture cutoff 2099 keeps legacy fixtures deterministic; the dedicated financial Clock test independently verifies the actual production boundary.


## Root packaged migration/HTTP verification — 21 October 2026

The independently copied final backend JAR SHA256 `b05fac9e1d048b445fe5ab58f7f77b2fa5a467a04de9a5f8cdd34d6953a23cac` passed32 real HTTP requests against owned PostgreSQL, including a populated V11→V12 upgrade with unchanged legacy identities, profiles, weights/shares and balances. Concurrent group/expense creates returned identical original201 responses; changed intent conflicted, stale PUT/DELETE preserved the winner, and original create replay after edit/deletion did not resurrect a resource. Child-only WEIGHTS1:2→2:4 retained final shares, advanced the exposed version and rejected the stale second edit. Owned application and database were removed. Artifact: `/workspace/issue-context/alignment-implementation/batch3/E02/final-http.json`. This source corresponds to implementation commit `eff12b4`; browser and independent review remain separate gates.
