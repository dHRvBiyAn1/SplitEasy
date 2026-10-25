# H01 expense audit and recovery candidate

Base: verified integrated dev `2df057cbbb045e7757d98fe24ded9ca8318812a2`, after PR104/A03 and PR105/C01; [dev CI40221010706](https://github.com/dHRvBiyAn1/SplitEasy/actions/runs/40221010706) passed backend/frontend/container checks, deployment skipped. Scope and acceptance: [H01 plan](../../superpowers/plans/2026-10-24-alignment-h01.md). Public contract and rollback restrictions: [expense recovery](../../api/expense-recovery.md).

V16 retains an expense deletion marker and immutable expense-specific before/after revisions with actor, server time, opaque resource version and optional operation identity. Current members can read history/recovery and explicitly restore a retained expense at its expected version. Every expense financial query excludes deleted rows; restored money preserves all six denominations and original split intent. E02 create receipts remain immutable. Imported baselines identify unknown recorders honestly. Group deletion still cascades expenses/revisions; group/payment recovery, permanent retention and durable global activity/pagination remain separate packages.

Sequential authenticated HTTP/PostgreSQL red→green slices established creation/replacement attribution, deletion retention and all financial exclusions, then exact versioned restoration in USD/EUR/GBP/INR/JPY/KWD. Rejected authorization/validation/stale/legacy/inactive-participant commands preserve history and balances; explicit re-add permits restoration. Deterministic PostgreSQL lock-wait probes cover restore/restore and both delete/edit orderings with primary HTTP/history/per-currency conservation assertions. Existing requester-removal race coverage now includes an already-waiting restore.

Sequential rendered Angular checks cover actors/dates, before/after money and meaningful split-input units, imported unknown actors, recoverable deletion, reviewed restoration, duplicate pending-write prevention, explicit uncertainty review and actionable stale/former-participant conflicts. Account/route boundaries cancel pending private reads/writes. A reproduced H06 omission left open panels stale; foreground refresh now shares pending reads, refreshes open history/recovery, preserves active history during detail reload and retains uncertainty guidance even after its expense disappears from the recovery list. Explicit review refreshes group/balances/live expenses and recovery without another write.

## Verification

Raw evidence: `/workspace/issue-context/alignment-implementation/batch5/H01`.

| Check | Command/result |
| --- | --- |
| Backend | Java21 `backend/mvnw -f backend/pom.xml -s /workspace/maven-proxy-settings.xml -B spotless:check verify`: **322 tests**, zero failures/errors; packaged successfully (`backend-full-verify.log`) |
| Frontend | Node22 `npm test -- --watch=false`: **335 tests / 43 files**, all passed (`frontend-full.log`) |
| Formatting | Full source `npx prettier --check 'src/**/*.{ts,html,scss,json}'` passed; the test-only file required a second formatter pass (`frontend-format-check.log`) |
| Production build | Node22 `npm run build -- --configuration production` passed (`frontend-build.log`); nonblocking initial 528.76kB / 500kB budget warning persists |
| Deployment checks | `python3 -m unittest discover -s deploy/tests -v`: **14 passed**; ShellCheck0.10 and Actionlint passed (`deployment-tests.log`, `shellcheck.log`, `actionlint.log`) |
| API/metadata | Official OpenAPI3.1 schema, duplicate-key parsing, **250 internal refs**, 81-row CSV/JSON and owner-confirmed USD metadata passed (`openapi-validation.log`) |

Frozen backend JAR SHA256: `a275bc8b134004cef518228b19c93c09fd6a781f59ea194eaefd5ae888d33de1` (`backend-frozen.jar`); `backend-source-manifest.json` identifies its source. Frontend source/assets are frozen in `frontend-manifest.json`. Runtime source remains unchanged after these checks.

Root independently passed **71 real HTTP responses** on this JAR after a populated V15→V16 upgrade, with cleanup confirmed (`batch5/upgrade-http.json`). Four imported cases cover KWD weights, USD equal, JPY missing original percentage intent degrading honestly to exact, and EUR adjustments. Truthful unknown actors, legacy409, financial exclusion, immutable create receipts, concurrent restore-once, former-participant refusal and explicit re-add/restore passed. Final owned native acceptance passed **81 real HTTP responses plus 151 browser API responses** at **1280/390** widths (`batch5/native-final/acceptance.json`), with zero page errors/overflow and cleanup confirmed. Root inspected desktop/mobile history screenshots. Native focus revalidation shows remote replacements in the already-open history/detail; a dropped actual successful restore response recovers through explicit full-state reads with no extra POST, then survives page reload. KWD precision and create/delete/restore/reload passed. The two independent Standards/Spec reviews remain final gates.

## Rollout limits

V16 follows V14/V15. Drain every old backend reader/writer before enabling H01: old queries count retained deleted rows even for USD. Keep schema/data on rollback and use a compatible forward fix. Do not revert to an old expense-reading binary or erase deletion markers as a rollback strategy. Existing hard-deleted expenses/groups cannot be recovered. No purge, dependency, production SMTP, deployment, main merge or worker remote publication is included.
