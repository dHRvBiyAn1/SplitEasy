# E02 / A02 / H06 integration verification

All three work branches start at verified dev
`e5b219773e54f890e56358f2a57de28f56d4bdff`. This report records an owned disposable
merge preview, reviewed PR candidates and local acceptance; no remote merge,
release, production delivery or deployment is claimed.

## Sources and merge compatibility

Final behavioral preview: `53820d6cce7d32a52301899cf966954d48b26efb`.
E02 application `eff12b4`, H06 application `4421aab`, A02 backend `e1616ee` and final
A02 frontend fixes through `c8733e4` are included. Subsequent evidence-only commits
do not change tested application behavior. Backend files are identical to the
verified combined `dd087d4`; the immutable packaged JAR SHA-256 is
`25e824e9685ce3560180a49ad4c0fdfb63d4999bac8e66c205aa270fc6e050cf`.

Shared App providers/tests preserve BrowserPlatformLocation, both `/elsewhere`
and `/account-action` test routes, H06 navigation/focus hooks and A02 public-route
classification. The stable router outlet and generation-aware initialization are
retained. Session tests preserve H06 network cancellation plus coherent A02 JWT
fixtures; pending-dialog setup preserves sessionStorage clearing and a coherent
owned login. E02 eager pending-write initialization and DELETE expectedVersion
remain. Agent-memory merges retain all package contracts/progress entries.

## Checks

- Java21/PostgreSQL16 Maven `spotless:check verify`: **280 tests**, zero failures,
  errors or skips, with packaged JAR. No backend source changed afterward.
- Final Node22 full Angular: **287 tests /37 files**; full-source Prettier passes.
  Production build passes. Its501.19kB initial bundle is1.19kB above the500kB warning
  budget (non-fatal); the standalone A02 build is below that warning budget.
- All **14 deployment configuration tests** pass. Official OpenAPI3.1 schema,
  duplicate-key rejection and **170 internal references** pass. The81-row capability
  CSV/JSON remains consistent and all38 package dependency references are acyclic.
- The real packaged rehearsal booted the known V11 JAR, populated two users, group,
  profile, weighted expense and balances, then upgraded the same owned Pg database
  through **V12 then V13**. Identity, registration/profile fields, integer amounts,
  final balances and original split intent survive. The old missing-version JWT
  remains usable at credentialVersion0; later credential changes revoke old tokens.
- **59 bootstrap/migration/HTTP responses** pass, separately from browser traffic:
  raced create/replay, changed-intent409, stale PUT/DELETE winner preservation,
  unchanged-shares child-intent version advancement, native SMTP reset/verification,
  password change, pending/confirmed replacement email, replay/purpose rejection and
  credential revocation. Challenge strings are absent from server logs.
- Production Angular/System Chromium passes **all six package/viewport flows** at
 1280×900 and390×900 against that same combined JAR. E02 loses real201 responses
  after commit, restores without dispatch on reload, requires deliberate identical
  operation retry and preserves one resource; independent winner/stale UI409 retain
  draft and server state. H06 uses native foreground focus and router navigation
  with an independent writer (17 reader API responses each). A02 passes all seven
  stages per viewport: explicit reset form/new-password login, verified profile,
  password-change logout/relogin, pending old-email login, explicit new-email
  confirmation/relogin, and same-tab mailbox reopening after native cross-tab B
  login without logging B out (58 observed API responses each, including independent
  checks). No business responses are fixtures; only loopback API transport/CORS is
  adapted. No page errors or horizontal overflow;20 screenshots retained.
- Mobile long replacement-email status reproduced overflow; two scoped layout
  properties now wrap content and permit grid shrinkage. The unchanged mobile
  browser assertion and visual inspection pass after that presentation-only fix.
- Owned Java app, Pg container/volume, loopback mailbox, static servers and browser
  contexts are stopped/removed. No production endpoint/data or outbound mail used.

Primary external evidence:
`batch3/final-acceptance-green/acceptance.json` (`passed:true`, cleanup confirmed),
its server log and20 screenshots; `final-287-tests.log`, `final-full-prettier.log`,
`final-mobile-wrap-build.log`, `combined-backend-verify.log`,
`combined-deploy-tests.log` and `final-reviewed-contract.log`. Standalone E02
backend263/frontend246, H06 frontend252 and A02 backend272/frontend261 checkpoints
are retained separately. External harnesses complement the repository's retained
MVC/PostgreSQL and rendered regressions and required CI in fresh checkouts.

## Review and rollout

[Independent review](third-batch-independent-review.md) keeps Standards and Spec
separate. Both final axes have zero remaining hard/material findings; the fixed
presentation-action switch is a nonblocking judgement call. Root browser findings
were reproduced and fixed through retained rendered/native/actual-Router tests.

Apply **E02 V12 before A02 V13**, or promote together. V1–V11 are immutable. Do not
mix old financial backend writers with version-aware writes. Default expense
version compatibility ends2026-11-19T00:00:00Z; configure a finite policy and ship
version-aware clients. Keep migrations/claims/versions on rollback. Old auth
binaries accept revoked JWTs: prefer a forward fix, or separately review all-instance
signing-key rotation/forced re-login before rollback. Prior V11 enum limits remain.

Native SMTP stays disabled by default. Production requires sender/credentials,
trusted HTTPS frontend origin, authenticated TLS/hostname checks/timeouts, and
explicit backend-only mail egress opt-in. Local SMTP acceptance is not external
inbox delivery. Durable mail/outbox, shared multi-instance budgets and device/
refresh sessions remain later scope. Every PR requires its exact final-head
backend, frontend and container CI checks before merging; deployment is skipped.
