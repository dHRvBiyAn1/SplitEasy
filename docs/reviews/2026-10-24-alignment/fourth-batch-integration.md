# A03 / C01 combined acceptance

Recorded branch base: `4072aa7f79cf67fbf5193566b84ab0b6bbcf1df5` (`dev`). Both issue branches started there. A03 is [PR #104](https://github.com/dHRvBiyAn1/SplitEasy/pull/104). The C01 PR carries the resolved combined tree, roadmap status and prepared H01 design. Merge A03 before C01 so V14 precedes V15; neither publication authorizes merge, main promotion or deployment.

## Reviewed changes

A03 candidate `d00ced2` includes the resource-service/logout401 corrections plus GitHub review's superseded-renewal fix. Captured user, device, session generation and authentication attempt prevent an older request from cancelling a newer explicit login/registration. Both rendered form paths reproduced failure before the minimal fix. C01 candidate `22197db` retains payment-only denominations even after reciprocal cancellation and returns409 upgrade guidance for a legacy non-USD edit before denomination comparison; each authenticated HTTP regression passed red→green. CurrencyInfo moved to the documented DTO package without a wire change.

Independent Standards and Spec reviews and follow-ups report zero remaining findings. Original reports and follow-ups are under `/workspace/issue-context/alignment-implementation/batch4/reviews/`; the A03 GitHub finding received a concrete fix reply and its thread was resolved. No financial/auth mock substitutes are used for the packaged acceptance below.

PR105's final group-headline review fix `fbc3676` uses the existing complete group balances read to retain settled/payment-only currencies independently of dashboard availability. Open/settled status is evaluated per currency. A rendered real617 KWD payment and mixed payment-only KWDzero/JPYopen with dashboard503 both passed red→green. Independent follow-up Standards/Spec reviews have zero findings; full combined frontend322, Prettier and production build pass. The unchanged309-test backend was verified by source hash without another run.

## Executed combined checks

Source candidate `fbc36761164a2c28ebb5f3a66f6db8a792ecac2b` combines both corrected implementations. Final publication adds documentation only; backend/frontend source manifests establish equivalence. The final frontend tree exactly matches the fully tested combined C01 candidate, so its322-test suite/build were not repeated in the preview.

| Check | Command or interface | Result |
| --- | --- | --- |
| Backend | Java21 `./backend/mvnw -f backend/pom.xml -s /workspace/maven-proxy-settings.xml -B spotless:check verify` | 309 tests; zero failures/errors/skips; Spotless and packaged JAR pass |
| Frontend | Node22 `ng test --watch=false` | 322 tests in 42 files pass |
| Formatting | Node22 `prettier --check 'src/**/*.{ts,html,scss,json}'` |Full source passes |
| Production build | Node22 `ng build --configuration production` |Pass; nonblocking 528.21kB initial vs 500kB warning; budget unchanged |
| Contract and backlog | Duplicate-key YAML, official OpenAPI3.1 schema, internal refs and 81 feature rows | 224 refs resolve; schema and feature mapping pass |
| Populated migration and HTTP | Real PostgreSQL16 with pinned V13 baseline, A03 V14 artifact, then final V15 artifact | 129 HTTP responses; all 15 recorded scenarios pass |
| Native browser | Production SPA and actual packaged API, Chromium 1280px/390px | 211 real API responses; both flows pass; zero page errors/overflow |

The HTTP rehearsal preserves pre-upgrade USD rows, profile, split intent, edited/deleted immutable receipts and replay identity; exercises secure cookie/origin/header/rotation/reuse/revocation and credential changes; keeps USD/JPY positions separate through settlement/departure; conserves tiny KWD splits; preserves currency on edits/preferences; rejects unsafe integer inputs; and independently verifies both final currency regressions. The migration sequence actually boots V13, then V14, then V15 without out-of-order mode.

Browser acceptance uses native HttpOnly cookies, Web Locks and cross-tab events. It drops an actual successful201 response, reloads without posting, and explicitly retries the original operation with exactly one expense. An actual old-account401 released after another account signs in preserves the replacement account and privacy. All-device revocation remains account-scoped. KWD 1234 minor units display 1.234 with 617/617 shares; description-only edit preserves denomination, its currency control is read-only, JPY 1.5 sends no POST and JPY 1 posts one minor unit. Twelve screenshots were saved; desktop/mobile screenshots were inspected. All owned Java/database/gateway/browser resources were removed (`cleanup:true`).

## Frozen evidence

Raw logs and artifacts stay outside git at `/workspace/issue-context/alignment-implementation/batch4`:

- `combined-backend-final.log`, `C01/settled-currency-final-tests.log`, `C01/settled-currency-format-check.log`, `C01/settled-currency-build.log`, `combined-contract.log`.
- `combined-final/acceptance.json`: final 129 HTTP/211 browser API acceptance; `passed:true`, `cleanup:true`.
- `combined-final/backend-verified.jar`: SHA256 `5b4c6e66c53e371a3c6df75ccf6786491e1d770420468bbe0cc120e202fa8d76`; `backend-manifest.json` pins every tracked backend file and successful command.
- `combined-final/frontend-browser/` and `frontend-manifest.json`: source manifest SHA256 `e2d8fac255a72acfe806c553ea9b976a6e1b8fa68294fd221f7623d5a3e36a0a`, artifact manifest SHA256 `08c344c8d3f6114808b4886b95a4dfa5cfaf8debeb6edf210022d9343a04fe28`.
- Pinned original V13 JAR SHA256 `25e824e9685ce3560180a49ad4c0fdfb63d4999bac8e66c205aa270fc6e050cf`; A03 migration artifact SHA256 `43a76e69321cc86980e33577a21f95c935f1a09af6784782dec4e31bc9250c4c`.

## Cutover and next phase

V14 must precede V15 or ship together. Drain old backends before enabling currency writes; legacy sidless JWTs force one sign-in. Never run mixed old/new financial writers. Keep additive schema and prefer a forward fix after currency writes; older auth rollback requires reviewed all-instance signing-key rotation and compatible frontend. No FX conversion is implied. See [session lifecycle](../../security/session-lifecycle.md) and [ledger contract](../../api/multi-currency-ledger.md).

[H01 history/recovery design](../../superpowers/plans/2026-10-24-alignment-h01.md) is prepared. Its implementation begins from verified dev after A03/C01 integration. Production Google Workspace SMTP configuration remains explicitly deferred. Exact PR-head CI and live review state are recorded in the external final handoff; merge/deployment is separate.
