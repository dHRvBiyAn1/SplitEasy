# F01 independent review

Base: `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca`. Reviewed candidate: `d22f09f406eb87812b7734678253b4251fa87322`. Independent Matt Pocock Standards and Spec axes ran separately. Subsequent commits retain evidence/project-memory only; application source is unchanged.

## Standards

## Standards

**No findings: 0 documented-standard breaches; 0 actionable smell judgments.**

Reviewed `git diff d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca...HEAD` at `d22f09f406eb87812b7734678253b4251fa87322` (commits `a90da19`, `d22f09f`). Both refs resolve and the diff is nonempty. Sources: current `Agents.md`, `README.md`, and `/workspace/SplitEasy/docs/agents/engineering-workflow.md`; no `CODING_STANDARDS.md` or `CONTRIBUTING.md` was found. Formatting enforced by tools was excluded.

Hard-rule checks:

- `backend/src/main/java/com/spliteasy/service/GroupWriteGuard.java:17` preserves membership-before-existence and repeats authorization after the database lock, including deleted groups, consistent with `Agents.md:99` and `Agents.md:112`. Financial write callers explicitly own READ COMMITTED; public aggregate reads retain REPEATABLE READ as required by `Agents.md:243`.
- `backend/src/main/java/com/spliteasy/service/ExpenseService.java:113` and `:143` reject inactive old participants before changing persisted state; the shared check at `:148` uses the standard `ConflictException`, consistent with `Agents.md:111` and `:127`. Existing central error handling retains WARN/409 and rollback behavior.
- `backend/src/main/java/com/spliteasy/service/PaymentService.java:40` authorizes before replay lookup; native conflict handling remains intact, matching `Agents.md:113`.
- `backend/src/test/java/com/spliteasy/controller/HistoricalParticipantIntegrationTest.java:34` and `FinancialMembershipRaceIntegrationTest.java:54` assert authenticated request/public-read outcomes against real persistence. Legacy SQL fixtures and real-query coordination fit `engineering-workflow.md:33`; financial results are not stubbed.

Judgment checks: considered all twelve supplied Fowler heuristics. `FinancialParticipantService.java:20` owns roster/ledger union, batch resolution and integrity validation, satisfying the reuse/no-N+1 rules (`README.md`, engineering principles; `Agents.md:146`). `GroupWriteGuard.java:17` consolidates a real shared locking/authorization protocol. Neither warrants a Middle Man or Speculative Generality finding. Remaining small similarities do not justify additional abstractions.

This is static review only; no tests were rerun and no application files were changed. Spec conclusions remain separate.


## Spec

# Spec review — F01

**No findings.** Reviewed independently against the design's financial-correctness section and foundation Tasks 1–3, using `git diff d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca...HEAD`. Both references resolve; HEAD is `d22f09f406eb87812b7734678253b4251fa87322`; the nonempty comparison contains financial commit `a90da19` and documentation commit `d22f09f`.

The implementation satisfies the bounded F01 requirements:

- Complete historical principals and separate access: `BalanceService.java:58`, `PairwiseBalanceService.java:65`, and batched `FinancialParticipantService.java:20` match foundation lines 65–72. Membership authorization remains separate; legacy, payment-only, dashboard and forbidden-access controls cover the required outcomes.
- Safe departure and persisted-history protection: `GroupService.java:146` checks net plus bilateral obligations; `ExpenseService.java:148` checks the old payer and every old share before PUT/DELETE and supplies the specified re-add guidance. S1/S2, departed payer outside shares, re-add correction and unrelated-history controls match foundation lines 86–95. No automatic payment/debt removal was introduced.
- Concurrency and replay: `GroupWriteGuard.java:17` reauthorizes after the database lock, including a missing row; every required mutation owns READ COMMITTED. Public aggregate REPEATABLE READ remains. Tests cover both financial/removal orders, all six waiting requester mutation paths, deleted-group 403, exact keyed retries and changed-intent conflict, matching foundation lines 109–118.

The reconciled tests retain financial assertions while accepting serialized edits and duplicate membership's domain conflict. A separate actual persistence conflict still proves transactional 409 translation. No schema/version/ETag work or F02 obligation projection was added; strict departure in both modes is explicitly accepted containment.

Verification here was read-only source/test inspection plus retained evidence inspection, not a new test run. The retained full-backend log reports 229 passing tests. `http-final-green.json` records all five scenarios passing with cleanup, tied to financial source `a90da19`; backend production source is unchanged at HEAD.


Both axes reported zero remaining findings for this package. Reviewers inspected source/evidence and did not claim new test executions.
