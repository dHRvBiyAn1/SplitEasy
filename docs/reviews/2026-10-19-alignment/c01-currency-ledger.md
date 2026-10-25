# C01 evidence and rollout

Requirements and serial execution: [C01 subsystem plan](../../superpowers/plans/2026-10-24-alignment-c01.md). Contract, compatibility and cutover: [denominated ledger](../../api/multi-currency-ledger.md). Recorded base: `4072aa7f79cf67fbf5193566b84ab0b6bbcf1df5`.

Backend candidate `92ac31ece0110a5fb0e0da7c7b5f58f362b30d31` passed Java21 `backend/mvnw -f backend/pom.xml -s /workspace/maven-proxy-settings.xml -B spotless:check verify`:289 tests, no failures/errors, packaged successfully. Frozen JAR SHA256 `0efbf05d5eacef9bcbb97866f397e7479caac94365036a74302bee847246ddb5`; per-file backend manifest has tree hash `414142d9b79cb21d1605d4f6335a48f9b666e9df21d2ddf8ed43f0bf89810f1f`. Artifact and manifest: `/workspace/issue-context/alignment-implementation/batch4/C01/checkpoint-92ac31e.{jar,json}`.

Sequential authenticated MVC/PostgreSQL reds→greens cover explicit legacyUSD fields, separate USD/JPY positions, independent settlement/departure, currency-partitioned dashboard/People/spending, legacy upgrade guidance, immutable V13-format create replay after deletion, catalogue/preference history and safe aggregate output. Fractional JSON rejection already existed and passed before implementation. The large-history safe-integer regression uses valid capped transactions and observes409 through authenticated dashboard HTTP.

Root independently ran51 authenticated HTTP checks against the frozen artifact after a populated V13 upgrade: oldUSD history and original edited/deleted receipts preserved; changed denomination conflicts; KWD1234 conserves617/617; immutable currency PUT; preferenceJPY does not relabelUSD; invalid fractional/missing/ambiguous/unknown currency rejected; tiny KWD weighted split retains zero and conserves1; dashboard currency rows and null mixed scalars reconcile. Report: `/workspace/issue-context/alignment-implementation/batch4/C01/independent-backend-full.json` (`cleanup:true`). Root also owns the final combined V13→V14→V15 and native-browser rehearsal; no unexecuted combined/browser result is claimed here.

Rendered Angular HTTP slices cover KWD creation, JPY amount precision, delayed-profile ownership, separate dashboard/People rows, obligation-currency settlement, immutable KWD editing with unchanged percentage basis points, old uncertain USD payment/expense drafts, every member currency in settings, KWD group history/shares, and all six profile choices. Old expense-only fixtures explicitly exercise unavailable-profile USD fallback; new C01 fixtures sign in through controlled HTTP with the real auth module. Final Node22 `ng test --watch=false` passed302 tests in40 files after full-source Prettier; `ng build` passed. The initial bundle is511.24kB and emits the configured nonblocking500kB warning. Full-suite selector checks exposed a captured imported catalogue field becoming undefined while the live catalogue remained correct; read-only getters preserve the live catalogue, and the original full302 regression passes. Diagnostic instrumentation was removed.

`openapi.yaml` passed duplicate-key YAML parsing,211 internal references and the official OpenAPI3.1 schema. Financial request branches document explicit minor units versus USD-only legacy input, currency-qualified responses/totals, version-header upgrade conflicts and catalogue/immutability. Logs: `frontend-final-tests-post-format.log`, `frontend-final-format-check.log`, `frontend-final-build.log` and `openapi-validation.log` under the raw-log directory.

Raw logs remain outside git at `/workspace/issue-context/alignment-implementation/batch4/C01`. Migrations: onlyV15 allocated; V14 must precede it. No dependency, SMTP, auth implementation, external provider, push, PR, merge or deployment is part of the worker result. Compatibility retirement is deferred.

## Final review corrections (25 October 2026)

Two authenticated MVC/PostgreSQL regressions ran individually red→green. Cancelled reciprocal JPY payments alongside USD expenses previously left numeric USD dashboard/group aliases; payment-history currencies now remain in the complete scope even when their obligations net to zero or the viewer is a third member. The regression observes null mixed scalar aliases and an explicit zero JPY totals row for both viewers. A headerless legacy amountCents PUT against a JPY expense previously returned400; the persisted-currency client contract now runs after membership/resource-scope authorization and before the immutable-currency comparison, returning409 with upgrade guidance without changing the expense. The unchanged catalogue wire record moved to dto.common per repository conventions.

Java21 `backend/mvnw -f backend/pom.xml -s /workspace/maven-proxy-settings.xml -B spotless:check verify` passed291 tests with no failures/errors and packaged successfully. Logs: `review-dashboard-{red,green}.log`, `review-legacy-put-{red,green}.log`, `review-format.log`, and `review-full-verify.log` in the raw-log directory. Frozen corrected artifact: `review-fixed.jar`, SHA256 `d2cf0d4a72a040388efd5b6e75886bf9e4bfcba92194de8ca88e98417d94f1e6`; final source/artifact hashes are recorded in `CANDIDATE.json`. Frontend/OpenAPI source is unchanged from the preceding verified candidate, so those checks were not repeated. The earlier51-request independent report applies to92ac31e; root owns updated combined backend/migration/browser verification and publication.


## Final combined acceptance

The corrected C01 candidate and A03 `d00ced2` passed combined backend309,
frontend319/42 files, full-source Prettier, production build and official
OpenAPI3.1/224-ref validation. Populated V13→V14→V15 acceptance passed127 HTTP
responses and169 actual browser API responses at1280/390, including both final
currency regressions, native cookie renewal/account isolation, dropped successful
response/reload/explicit same-operation retry, and KWD/JPY forms. Zero page errors
or overflow; screenshots inspected and all owned resources removed. See the
[fourth-batch integration evidence](../2026-10-24-alignment/fourth-batch-integration.md)
for frozen artifact hashes, commands and migration/rollback limits. The C01 PR
carries the tested integration with companion A03 PR #104 and H01's prepared design;
merge A03 first. Publication does not authorize merge/deployment.

## PR105 settled-currency headline correction (25 October 2026)

The rendered group detail previously inferred denominations from outstanding pairwise obligations: recording the final KWD settlement changed its headline to “USD All square.” Group detail now also reads the existing complete group-balances endpoint, which retains zero rows for persisted expense/payment currencies, and seeds its headline currencies from those rows. Net amounts and open-balance labels still use the viewer's pairwise obligations, independently for each currency. The additional read shares the existing route/session cancellation and retry scope; group correctness remains independent of dashboard availability.

Two sequential rendered HTTP regressions ran red→green: an actual Record payment617 KWD action retains “KWD All square” after its final obligation disappears; a payment-only zero KWD position alongside open JPY retains both headlines, labels KWD as settled, and still works after a dashboard503. Existing route, session, failed-refresh, pending-read and versioned-delete fixtures cover the complete-ledger read; its own pending/failure scenario was added. Node22 post-format full suite passed322 tests in42 files, full-source Prettier passed, and the production build passed with the existing nonblocking528.21kB/500kB initial warning. Backend and OpenAPI source are unchanged from combined basec1422b1; backend309 was not repeated.

Raw logs: `settled-kwd-{red,green}.log`, `settled-mixed-{red,green}.log`, `settled-currency-final-tests.log`, `settled-currency-format-check.log`, and `settled-currency-build.log` under the C01 evidence directory. Frozen frontend: `frontend-settled-currency`; exact source/artifact hashes: `settled-currency-frontend-manifest.json` and `CANDIDATE.json`. Root owns expanded native-browser acceptance, review resolution and updated PR-head CI; the worker makes no remote writes.
