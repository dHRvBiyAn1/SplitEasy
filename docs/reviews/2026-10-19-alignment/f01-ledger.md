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

No migration, new dependency, balance table, historical amount rewrite, membership access expansion, or frontend source change is included. `openapi.yaml` describes the stricter removal/history guards and complete financial participant enumeration.

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

Integration owner runs the standalone real-HTTP S1/S2/access/many-person probe against the packaged JAR and records separate Standards/Spec reviews at the recorded base. Those additional results must be recorded before a reviewed PR claims their completion.

## Limits and rollback

This is conservative containment. The audit's S4 simplified-settlement/direct-obligation mismatch remains F02 work; net-zero intermediaries can be blocked even when a future simplified projection could allow departure. Historical damaged ledgers become visible, but correction still requires explicit re-add and mutation. No automatic repair or old membership timestamps are fabricated.

The lock serializes writes within a group, including keyed payment replays; unrelated groups remain independent. There is no optimistic stale-form detection. No schema rollback is needed, but reverting this application change restores the unsafe removal/history race and can hide already-orphaned participants again. No production mutation, push, PR, merge, or deployment is performed by this verification record.
