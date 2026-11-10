# F01 historical ledger and safe membership changes

Date: 19 October 2026. Branch: `fix/alignment-f01-historical-ledger`.
Recorded base: `origin/dev` at `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca`.
Scope: Tasks 1–3 of the [foundation plan](../../superpowers/plans/2026-10-19-splitwise-alignment-foundation.md), following the [design](../../superpowers/specs/2026-10-19-splitwise-alignment-design.md) and [selected engineering workflow](../../agents/engineering-workflow.md).

## Resulting behavior

- Net balances enumerate active members plus every persisted expense/payment principal; pairwise balances include historical and payment-only counterparties. Identity resolution uses a roster-ID query and one batched `findAllById`, independent of the number of participants. Financial participation grants no access or active-roster membership.
- Leaving/removing requires zero net **and** no nonzero bilateral obligation. The reproduced net-zero intermediary remains a member after the rejected 409; expenses and payments remain unchanged.
- PUT/DELETE inspect the old expense's payer and every old share participant. Inactive references return 409: “Re-add former participants before changing this expense.” Re-adding the same account permits explicit correction without changing payments or inventing participant identities.
- Expense POST/PUT/DELETE, payment POST, membership add/remove, and group DELETE acquire the same PostgreSQL group-row write lock. Membership is checked before the lookup and again after waiting, including when concurrent group deletion leaves no row. Write owners use READ COMMITTED; read-only aggregate owners retain REPEATABLE READ.
- Keyed payments retain exact 201 replay and changed-intent 409. Concurrent financial edits serialize and can both return 200; separate stale forms still have no version/ETag contract. A real stale-row persistence failure remains a transactional 409.

No migration, new dependency, balance table, historical amount rewrite, or membership access expansion is included. The PR #96 follow-up below adds the matching frontend departure controls. `openapi.yaml` describes the stricter removal/history guards and complete financial participant enumeration.

## Behavioral evidence

Primary assertions use authenticated MVC requests and subsequent authorized expense, payment, group, balance, or dashboard reads against real PostgreSQL. SQL only seeds legacy damage or coordinates an independently committed persistence conflict. Repository spies and `pg_blocking_pids` coordinate races; they do not replace aggregate results. Test requests own their production transaction boundaries.

Per-slice logs are retained in `/workspace/issue-context/alignment-implementation/F01/` in the execution workspace:

| Scenario | Red evidence | Green/control evidence |
| --- | --- | --- |
| Historical counterparty survives deleted membership | `01-historical-red.log`: missing counterparty/ledger entry | `01-historical-green.log`; damaged settled history and payment-only controls in `02`–`03` |
| Financial participation does not authorize access | Existing access control | `04-historical-access-control.log`: departed account gets 403 for real/missing groups; active roster/dashboard group count excludes it |
| S1: net-zero intermediary with two direct obligations | `05-S1-red.log`: removal returned 204 | `05-S1-green.log`: requester/self removal 409; exact balances and history preserved |
| S2: settled departed participant's original expense | `06-S2-delete-red.log`: DELETE 204; `07-S2-put-red.log`: replacement PUT 200 | `06`–`07` green logs: both 409; re-add permits mutation; original payment survives |
| Ordinary history and departed payer outside shares | Existing normal editing behavior | `08-normal-history-control.log`, `09-payer-history-control.log` |
| Requester leaves after initial authorization | `10-membership-race-red.log`: stale expense returned 201 | `10-membership-race-green.log`: 403, no expense/payment leak |
| Financial write first; removal first | Actual independently blocked PostgreSQL transactions | `11-financial-first-controls.log`, `12-removal-first-controls.log`: final obligations prevent removal, or inactive counterparties prevent new rows |
| Already-waiting requester leaves | Actual group lock wait | `13-all-waiting-mutations-controls.log`: six mutation callers return 403; `14-group-delete-waiter-control.log`: deleted-group waiter also returns 403 |
| Payment identity and duplicate member races | Existing operation/uniqueness regressions | `15-payment-retry-regression.log`: 19 pass; `16-membership-uniqueness-regression.log`: serialized member add 200/409 and real registration unique violation |
| Edit serialization and persistence conflict | Existing overlapping edit coordination reconciled with the lock | `17-serialized-edits-regression.log`: both edits 200 and coherent final ledger; `18-persistence-conflict-control.log`: real stale update returns 409 |
| Snapshot removal coordination | `20-snapshot-removal-existing-red.log`: old test waited for a payment the removal lock blocks, causing timeout/500 | `25-snapshot-green.log`: all 13 snapshot cases pass; atomic two-payment commit precedes waiting removal, preserving exact 100/100/−200 final nets and membership |

The other public read snapshot cases retain their original intervening-commit choreography and exact financial assertions. Only the removal write's choreography changed: it now proves a real lock wait before evaluating the committed two-payment ledger. Other race suites move barriers before lock acquisition where the old barriers required concurrent writers inside a serialized section.

## Verification

Commands run from this branch with Java 21 (`/workspace/toolchains/jdk-21.0.12.1+1`), PostgreSQL 16 Testcontainers through `DOCKER_HOST=unix:///var/run/docker.sock`, Maven settings `/workspace/maven-proxy-settings.xml`, and Node `v24.19.0` already validated in this workspace. CI remains configured for Node 22.

| Command | Result | Log |
| --- | --- | --- |
| `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B -Dtest=BalanceSnapshotIntegrationTest test` | 13 passed | `25-snapshot-green.log` |
| `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` | Passed: 229 tests, zero failures/errors/skips; packaged JAR | `26-backend-full-verify.log` |
| `cd frontend && npm test -- --watch=false` | 26 files, 187 tests passed | `21-frontend-tests.log` |
| `cd frontend && npx prettier --check 'src/**/*.{ts,html,scss,json}'` | All matched files passed | `22-frontend-prettier.log` |
| `cd frontend && npm run build -- --configuration production` | Passed | `23-frontend-build.log` |
| `git diff --check` | Passed after final verification | Workspace command |

Integration-owner real HTTP rehearsal passed 83 requests against the packaged JAR (code commit `a90da192f730d79b8625cc6152cdd4960a0bfa42`, SHA-256 `01e5f44890efdd231c9c2fb8fb948562ad2ccb3e46969754035e306ec7544f8d`). Five scenarios passed: JSON contract controls; S1 intermediary removal blocked; S2 departure/history guards and explicit re-add/correction; legacy damaged history visible without access; eight-person ledger with exact owner +70 and seven −10 nets. The original baseline JAR made this harness fail on unsafe removal204 instead of409. Raw evidence: `/workspace/issue-context/alignment-implementation/F01/http-final-green.json`; all credentials omitted, owned Java process stopped and PostgreSQL container/volume removed. Source confirms fixed aggregate queries and one batched user lookup; the eight-person HTTP case verifies behavior rather than asserting internal SQL counts. Independent Standards and Spec reviews reported zero findings; see `f01-independent-review.md`.

## Limits and rollback

This is conservative containment. The audit's S4 simplified-settlement/direct-obligation mismatch remains F02 work; net-zero intermediaries can be blocked even when a future simplified projection could allow departure. Historical damaged ledgers become visible, but correction still requires explicit re-add and mutation. No automatic repair or old membership timestamps are fabricated.

The lock serializes writes within a group, including keyed payment replays; unrelated groups remain independent. There is no optimistic stale-form detection. No schema rollback is needed, but reverting this application change restores the unsafe removal/history race and can hide already-orphaned participants again. This record covers local verification; publishing a reviewed PR is the next authorized delivery step. No production mutation, merge or deployment was performed.


## PR #96 review follow-up: settings departure controls

The review found a caller mismatch: `GroupService.removeMember` already rejected a zero-net member with outstanding bilateral debts, but settings used only net balances, labeled that person settled, and enabled removal/leave. The approved HTTP and rendered Angular seams reproduce it; this is a direct caller-data omission, so no speculative diagnosis or internal-collaborator mock was needed. Matt Pocock TDD/debugging/interface guidance and Ponytail informed the bounded fix.

Implementation candidate: `6d416f0c529994d8fd6727ee75b68822bd5980ff`, reviewed against predecessor `b44d3b4`. Settings now loads group, net balances and raw obligations together; a member with offsetting debts is “even overall”, with each counterparty's full name/email and exact amount visible. Removal and leaving require zero net and no raw obligation in either simplification mode. The creator remains protected; fully settled members remain eligible. Pending/failed reads disable removal/leave, including an existing confirmation after a refresh failure retains the old group.

The existing `/balances/mine` is only the caller's pairwise view, and `/debt-simplification` discards the intermediate bilateral edges. The additive member-only `GET /api/groups/{groupId}/balances/obligations` returns `DirectObligation[]`: `{from: UserSummary, to: UserSummary, amountCents: positive integer cents}`, meaning **from owes to**. It includes historical financial counterparties; canceled pairs and self/zero edges are absent. It uses two grouped expense/payment reads and the existing batched financial identity resolver, under REPEATABLE READ, rather than one read per candidate. Caller membership is checked before any financial read, retaining 403 for existing and nonexistent groups. No other response shape changes. The companion PR #97 documents this same contract.

Red/green logs are retained, with credentials redacted, under `/workspace/issue-context/alignment-implementation/F01/pr96/`:

| Seam | Reproduction | Passing result |
| --- | --- | --- |
| Authenticated MVC/PostgreSQL | `obligations-red.log`: new raw read returned 404 instead of 200 for the known A→B/B→C intermediary | `obligations-green.log`: exact two directed 1000-cent debts; rejected removal/self-leave preserves ledger |
| Rendered Angular/HTTP | `ui-red.log`: Bob's zero-net Remove control was enabled despite both raw obligations | `ui-green.log`: disabled removal/leave, both counterparties and amounts shown, settled Dan still eligible |
| Broader public behavior | Existing safeguards retained | `backend-focused.log`: 29 passed, including historical/payment-only counterparties, former-member 403 for real/missing groups, opposing expenses, payment cancellation/overpayment reversal, and settled departure |
| Rendered ownership and controls | Same approved seams | `ui-focused.log`: 12 passed, including creator/self/other candidates, both simplify settings, settled leave DELETE, route cancellation, and retained-group refresh failure with an open confirmation |

Final verification at application source `6d416f0`, using JDK `21.0.12.1`, the healthy local Docker 28.4.0 socket, and Node `22.23.3`:

| Command (from resource directory) | Result | Retained log |
| --- | --- | --- |
| `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 PATH=/workspace/toolchains/jdk-21.0.12.1+1/bin:$PATH ./mvnw -s /workspace/maven-proxy-settings.xml spotless:check test package` | Backend **230/230**; no failures/errors/skips; executable Spring Boot JAR | `backend-full.log` |
| `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false` | Frontend **193/193**, 27 files | `frontend-full.log` |
| `npm exec --yes --package=node@22 -- node node_modules/prettier/bin/prettier.cjs --check 'src/**/*.{ts,html,scss,json}'` | All source files formatted | `prettier.log` |
| `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build` | Production build passed without warnings | `frontend-build.log` |
| `git diff --check` | Passed | Final workspace check |

`verification.json` ties these results and the packaged JAR SHA-256 to the candidate. Focused tracer/controls initially used the workspace default Node24; the full frontend suite, full formatting check and final build explicitly used Node22. The integration owner reported separate Standards/Spec delta reviews with zero findings; their retained reports live outside this worker's edits.

 This follow-up adds no schema/dependency change or production operation. Backend removal remains authoritative if financial activity changes after the read; this is the existing request/response UI model, not a new concurrency guarantee. No separate browser, physical-device or screen-reader rehearsal is claimed for this follow-up.
