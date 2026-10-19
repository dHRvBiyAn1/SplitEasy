# A01 — server-owned current profile

Base: `337858d8197d9228d80a03dfcc7d3aef654b4e04`. Scope: approved HTTP/PostgreSQL, rendered Angular and owned browser seams. Matt Pocock local TDD workflow; Ponytail minimal implementation.

`GET/PATCH /api/me` uses the authenticated principal only and returns `{id,email,displayName,phone,avatarColor,preferredCurrency,createdAt}`. Email, identity, password/token data cannot be changed here. Null/omitted PATCH fields are unchanged. Display names contain a non-whitespace character and are at most 100 characters; phone is unverified contact information at most 50 characters. Avatar accepts the existing five exact swatches and currency accepts USD/EUR/GBP/INR. No locale field: there is no UI locale choice to persist. Invalid requests reject atomically. Currency does not change any stored amount or displayed money denomination; all legacy money remains USD.

Profile state resets synchronously by immutable identity plus session version, before effects. Requests cancel at session boundaries and guards reject stale request generations. Fresh profile name updates only auth presentation, preserving ID/email/token. Auth does not depend on ProfileService. Account-owned local preferences remain available until explicit import succeeds; cancelling preserves the local record. Import has no ownerless migration and never copies the old device first-visit date. Theme/palette remain device-wide.

V10 adds columns/defaults and choice constraints without rewriting V1–V9 or `created_at`. Existing users get empty phone, green avatar and USD preference. Old app versions ignore the extra columns and can register users using defaults; rollbacks should leave V10 and its data intact. Dropping columns destroys preferences; do not downgrade Flyway history. Deploy V10 before E01 V11 or promote both together. No refresh-token/revocation/email-verification claims.

## Evidence

- Real JWT/PostgreSQL HTTP tracer failed with 404 before implementation, then passed profile save→fresh-login→GET with original registration date.
- Rendered Angular HTTP tracer failed due to absent `/api/me`; green proves fresh name/date, read-only email, PATCH shape, pending save and accepted account-name update.
- HTTP cases cover unauthenticated/invalid token controls, ignored payload targets/email, invalid fields with unchanged profile and null no-ops.
- Rendered/account-state cases cover A→B→A, injector reload, repeat login, register/logout, pending reads/writes cancellation, overlapping refreshes, save/refresh errors and explicit owned import/cancel.

## Worker verification

- Backend: `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 PATH=/workspace/toolchains/jdk-21.0.12.1+1/bin:$PATH ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` (from `backend/`). Passed: 234/234 tests, Spotless check and runnable JAR packaging; focused `ProfileIntegrationTest` is 4/4 green.
- Frontend: `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false` (from `frontend/`): 226/226 green.
- Production build: same Node22 Angular CLI with `build --configuration production`: passed without warnings.
- Full source: `npx prettier --check "src/**/*.{ts,html,scss,json}"`: passed.
- OpenAPI: official OAS3.1 schema `https://spec.openapis.org/oas/3.1/schema/2025-09-15` validated with Python jsonschema; 138 internal references resolved. `git diff --check` passed.

Logs live at `/workspace/issue-context/alignment-implementation/batch2/A01/`; worker commands/results are recorded above. The root owns independent packaged HTTP, populated V9→V10/fresh-schema migration and real Chromium rehearsal against the committed candidate; those results are not claimed by this worker.


## Independent review remediation

Standards review reported zero findings. Spec review found that both profile templates still displayed cached auth email. A rendered differing-email test failed on the cached address, then passed after view/edit templates used the accepted `/api/me` email. Auth identity/email/token stay unchanged and PATCH still excludes email; this changes read-only presentation only.

The proposed stale import-cache issue did not reproduce. A rendered successful import followed by component destruction/recreation with the same injected ProfileService does not re-offer preferences. `legacyPrefs` also tracks `auth.user()` directly; accepting the response updates the auth presentation object and invalidates that computed before the successful import removes its storage key. Keep the regression without an unnecessary service change. The same rendered scenario now also verifies failed import retains the current owner's record, retry is explicit, cancel preserves it, and another owner's record remains untouched.

Review follow-up logs: `review-email-red.log`, `review-email-green.log`, `review-import-recreate.log`, `review-frontend-full.log`, `review-prettier-full.log`, `review-build.log` under the A01 evidence directory. Full Node22 frontend: 226/226; full-source Prettier passed. Backend is unchanged from the candidate's 234/234 verified run. The root reported the original candidate passed independent populated V9→V10, 21 packaged HTTP requests and desktop/mobile browser checks; it owns the final rebuilt candidate rerun.
