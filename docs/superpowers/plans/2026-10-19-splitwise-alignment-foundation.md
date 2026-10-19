# Splitwise Alignment Foundation Implementation Plan

> **For agentic workers:** Follow the [Matt Pocock skill workflow](../../agents/engineering-workflow.md), using `tdd`, `diagnosing-bugs`, `code-review`, and `pr` where applicable. Preserve the user's three-thread limit and `dev` branch/PR workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contain reproduced financial lifecycle defects, make unfinished controls honest, and bring the shipped API documentation into line before wider feature work.

**Architecture:** Derive financial counterparties from ledger references independently of active membership. Serialize financial mutations per group, conservatively reject unsafe departures/history changes, and retain the current frontend/backend architecture. Profile truthfulness and contract documentation are independent branches.

**Tech Stack:** Java 21/Spring Boot 4.1, PostgreSQL 16/Testcontainers/Flyway, Angular 21/Vitest, Maven, CI Node 22.

**Spec:** [Proposed alignment design](../specs/2026-10-19-splitwise-alignment-design.md). Parent: [full roadmap](2026-10-19-splitwise-alignment.md).

## Global Constraints

- Java 21, Spring Boot 4.1, PostgreSQL 16/Flyway, Angular 21 standalone components/signals, Vitest; CI Node 22.
- Maximum three concurrent implementation workstreams. No Astra; Sol reasoning must not exceed high. Models and branches are specified per work package.
- Branch from the latest checked `origin/dev`; implementation PRs target `dev`. Integrate and verify shared changes before starting dependent branches. Release promotion is a separate `dev` to `main` PR.
- The user authorized implementation on 19 October 2026, including separate branches and PRs to dev. Current work is the dependency-ready first batch F01/F03/F04. Merge, production deployment, new remote issues and paid/provider setup remain separate actions requiring applicable authorization.
- Keep UUID identities, DTO boundaries, `MembershipGuard`, `Emails.normalize`, and 403-before-404 for unauthorized group access. Financial participation never grants group access.
- Keep derived balances, integer minor-unit arithmetic, the current 1_000_000_000_000 per-entry bound, overflow-safe validation, and consistent financial read snapshots. Never add floats or stored mutable balance totals.
- Keep current flat group editing permissions and creator-only group deletion until a documented lifecycle change; do not add roles solely for parity.
- Keep payment operation identity and exact replay semantics; no automatic retry of a changed write, conflict, or real-money transfer.
- Use additive, forward-compatible migrations. Never reinterpret legacy amounts as a different currency or delete historical payments to force balance agreement.
- Use existing components, services, libraries, and platform capabilities before adding dependencies. No generalized ledger engine, event-sourcing rewrite, microservices, or speculative abstraction.

## Review Focus

1. Existing deleted memberships must not make counterparties disappear or grant them access: Task 1 seeds old damage and checks 403-before-404.
2. Removing a net-zero intermediary must not strand direct obligations: Task 2 reproduces S1 and asserts unchanged rows on 409.
3. A settled departed member's history must not reopen invisibly: Task 2 reproduces S2 and blocks both deletion and participant-replacing PUT.
4. Financial writes waiting behind removal must recheck authorization/membership: Task 3 coordinates real PostgreSQL transactions in both orders.
5. Delayed UI writes and local profile fields must not claim server success or overwrite a new account: Tasks 4–6 test account changes, errors, draft retention, and pending modal closure.

## Ownership and first batch

| Stream | Model | Branch from current `origin/dev` | Owns | Excludes |
| --- | --- | --- | --- | --- |
| F01 | `gpt-6.1-sol`, high | `fix/alignment-f01-historical-ledger` | Backend services/repositories and financial integration tests | Frontend, OpenAPI, shared project memory |
| F03 | `gpt-6.1-sol`, medium | `fix/alignment-f03-honest-interactions` | Profile and group/detail/modal Angular code/tests | Backend, OpenAPI, shared project memory |
| F04 | `gpt-6-luna`, medium | `docs/alignment-f04-contract` | `openapi.yaml`, API documentation, proposed memory correction | Application code, financial policy implementation |

The integration owner applies/reconciles `Agents.md` changes after all three streams report, so no worker overwrites another's progress. Reviewer work counts toward the maximum three active streams; replace a completed implementer slot with a reviewer rather than adding a fourth. Use existing worktree/PR conventions when execution is requested. Implementation was authorized on 19 October 2026; the first batch uses these three worker streams.

Backend source root below: `backend/src/main/java/com/spliteasy/`; backend test root: `backend/src/test/java/com/spliteasy/`. Listed new names are proposed exact implementation boundaries, not files already present.

## Testing seams and execution discipline

The proposed seams are: authenticated MVC/HTTP requests and subsequent authorized reads for financial/access behavior; rendered Angular interactions with controlled HTTP responses for UI ownership and errors; and a real browser against an owned local application for keyboard, modal and end-to-end flow evidence. HTTP tests cover real PostgreSQL transactions but not physical-device accessibility; UI tests cover request/render ownership but do not prove backend behavior; browser checks cover the integrated path but do not replace deterministic race tests. Confirm these seams when authorizing the batch, before new tests are written.

Apply Matt Pocock's TDD loop **one scenario at a time**. In each task below, choose one listed scenario, write one failing test, run red, implement the minimal change, and run green; then return for the next scenario. The grouped scenario lists specify required coverage, not permission to write all tests before implementing any behavior. Refactor during review after behavior is green. Assert persisted outcomes through authorized expense/payment/balance reads; SQL seeding of legacy damage and race coordination are fixture/diagnostic support. Capture the branch base SHA at kickoff for the separate Standards and Spec reviews.

## F01 / Task 1 — Financial participants independent of roster

**Files:**

- Create: `backend/src/main/java/com/spliteasy/service/FinancialParticipantService.java`.
- Modify: `backend/src/main/java/com/spliteasy/service/BalanceService.java`.
- Modify: `backend/src/main/java/com/spliteasy/service/PairwiseBalanceService.java`.
- Use existing: `repository/UserRepository.java`, membership and aggregate repositories. Add a batched fetch only if the current `findAllById` cannot provide the required data.
- Create: `backend/src/test/java/com/spliteasy/controller/HistoricalParticipantIntegrationTest.java`.
- Retain/check: `controller/BalanceSnapshotIntegrationTest.java`.

**Interfaces:**

- Produces `FinancialParticipantService.resolve(UUID groupId, Collection<UUID> ledgerUserIds): Map<UUID, UserSummary>`. Merge active roster IDs with all supplied ledger IDs and batch-resolve users; no per-person query. Package-internal, not an authorization endpoint.
- `BalanceService.computeBalances(UUID requesterId, UUID groupId)` and `PairwiseBalanceService.forGroup(UUID requesterId, UUID groupId)` retain current public DTO shapes/signs.
- Balance aggregation supplies the union of paid/owed/settled-in/settled-out keys. Pairwise aggregation supplies all counterparty keys, including payment-only counterparties. Current zero-balance members remain represented as before.

- [ ] **Write regressions** named `historicalCounterpartyRemainsVisibleWithoutMembership`, `damagedSettledDepartureStillReturnsZeroSumLedger`, `paymentOnlyCounterpartyIsIncluded`, and `historicalParticipationDoesNotAuthorizeGroupAccess`. Use existing `AbstractIntegrationTest` registration/JSON helpers. Seed only the legacy-invalid state via repository/SQL after creating valid API expenses/payments; this reproduces old data without depending on the future guarded delete endpoint.
- [ ] **Pin expected values.** Legacy S2 fixture: A paid 2,000 split equally A/B; B paid A 1,000; old code removed B and deleted the expense. Remaining ledger is A −1,000 and B +1,000, sum zero. A's pairwise view contains B −1,000. B's group GET/balance/payment/expense routes remain 403, including nonexistent-group probes. No active roster or dashboard group count includes B as a member.
- [ ] **Run red:** `cd backend && ./mvnw -B -Dtest=HistoricalParticipantIntegrationTest test`. Expected failures: B missing and nonzero returned group sum; authorization control cases may already pass.
- [ ] **Implement** the batched participant resolver and change only roster-based enumeration in the two aggregation services. Keep aggregation queries, requester membership checks, repeatable-read snapshots, sort behavior, and stored history intact. Missing referenced users are an integrity error, never a silently discarded amount.
- [ ] **Run green:** the same class plus `BalanceSnapshotIntegrationTest` and existing balance/dashboard integration tests selected from the current source tree. Expected: all new tests pass; all pre-existing financial snapshot assertions still hold. Confirm query count stays bounded with a many-person fixture, rather than adding one query per counterparty.
- [ ] **Checkpoint** this independently reviewable read repair once implementation/commit is authorized. Do not call lifecycle defects fixed yet; Tasks 2–3 prevent new damage.

## F01 / Task 2 — Conservative safe leave and history mutation

**Files:**

- Modify: `service/GroupService.java`, `service/ExpenseService.java`.
- Create: `service/HistoricalExpenseGuard.java` if the check is shared by PUT and DELETE; otherwise one private service method is sufficient.
- Test: `controller/GroupSettingsIntegrationTest.java`, `controller/HistoricalParticipantIntegrationTest.java`.

**Interfaces:**

- Removal uses existing net balance and internal `PairwiseBalanceService.compute(targetUserId, groupId)` with the complete participant resolver from Task 1. The requester is authorized separately; computing the target's financial view must not require logging in as the target.
- Produces `requireActiveExpenseParticipants(Expense expense): void` (a private method or guard method). Examine the **old persisted expense's** payer and every share participant before applying PUT or DELETE. If anyone is no longer active, throw `ConflictException` with “Re-add former participants before changing this expense.” Do not weaken new-expense membership validation.
- A settled departure remains 204. Open net or any nonzero direct counterparty remains 409 with wording that explains outstanding obligations, rather than just the net total.

- [ ] **Write S1 regression:** A creator/B/C; B pays 1,000 entirely owed by A, C pays 1,000 entirely owed by B. Assert A −1,000/B 0/C +1,000 and B has two nonzero direct edges. Removing B must return 409, keep membership, and preserve all ledger/payment rows. Also test creator-removal and unauthorized requester controls.
- [ ] **Write S2 regression:** A pays 2,000 equal A/B; B→A payment 1,000; both nets and bilateral balances zero; B leaves 204. A's DELETE of the original expense returns 409 and leaves expense, shares and payment unchanged. A's PUT that replaces B with only current members also returns 409. Re-add B by existing email, then perform the intended edit/delete: it is permitted and recomputed balances include both people.
- [ ] **Write a normal-history control:** description/amount edits and deletes involving only active people still work under existing split validation and flat permissions. A departed user with no historical participation does not freeze unrelated expenses.
- [ ] **Run red:** `./mvnw -B -Dtest=GroupSettingsIntegrationTest,HistoricalParticipantIntegrationTest test`. Expect the old implementation to return 204 in unsafe S1/S2 mutations; assertions prove failed requests leave persisted rows unchanged after the fix.
- [ ] **Implement** net-plus-bilateral removal and old-expense participant guards. Return domain conflicts, never silently re-add a person or erase a payment. Do not add a financial-history write model, new roles, or a schema migration for this containment fix.
- [ ] **Run green** with the same command and expense/split bounds integration tests. Capture authenticated HTTP evidence for S1/S2 on disposable PostgreSQL. Record that simplified-mode zero-net intermediaries are conservatively blocked until F02, and that previously damaged histories require explicit re-add/correction.
- [ ] **Checkpoint** the guarded lifecycle change when authorized; document the temporary restriction in the PR.

## F01 / Task 3 — Make the guard hold under concurrent writes

**Files:**

- Create: `service/GroupWriteGuard.java`.
- Modify: `repository/GroupRepository.java`, `service/GroupService.java`, `service/ExpenseService.java`, `service/PaymentService.java`.
- Create: `controller/FinancialMembershipRaceIntegrationTest.java`.
- Retain/check: `controller/BalanceSnapshotIntegrationTest.java`, `controller/PaymentFlowIntegrationTest.java`, existing payment-operation race tests and `controller/ExpenseEditConflictIntegrationTest.java`.

**Interfaces:**

- `GroupRepository.findByIdForUpdate(UUID groupId): Optional<Group>` uses `PESSIMISTIC_WRITE` on the group row.
- `GroupWriteGuard.lockForMember(UUID groupId, UUID requesterId): Group`: require membership, acquire row lock, then require membership again. Recheck even if the locking lookup returns no row after a concurrent group deletion, before choosing the response. Nonmember requests retain 403-before-404; a requester removed while waiting must fail after the lock.
- Every expense create/update/delete, payment create, member add/remove, and group delete locks the same group row before domain validation/mutation. Use READ COMMITTED at the owning write boundary. Lock before replay lookup too, but retain membership-before-replay and V9 native `ON CONFLICT` behavior. Current setting-only PATCH can remain unchanged until F02 needs mode-change validation.
- Public read transactions stay REPEATABLE READ. Nested aggregate reads within a guarded write share that transaction; the common group lock prevents another financial writer from changing their inputs mid-check.

- [ ] **Write coordinated races** for removal versus new expense, removal versus settlement/payment, self-leave versus an already-waiting write, and two keyed payment retries. Use latches/test spies and real independent database transactions, following `BalanceSnapshotIntegrationTest`; no timing sleeps as the proof.
- [ ] **Pin both orders:** if the financial write commits first, removal sees its final obligations and conflicts when unsettled; if removal commits first, a newly invalid participant/write is rejected and no row appears after removal. A requester who left while waiting gets 403, an inactive counterparty gets the documented validation/conflict, and no outcome leaves an unrepresented obligation. Exact payment retries still return the original 201; changed intent still returns 409.
- [ ] **Run red** on `FinancialMembershipRaceIntegrationTest` and demonstrate at least the actual membership/write race fails on the unguarded base. The race was a static concern in the audit; do not claim prior runtime reproduction unless this test proves it.
- [ ] **Implement** the row guard and consistent mutation entry points. Preserve lock ordering and avoid a second connection/requires-new read inside the guarded transaction. Rework only tests whose old coordination assumed financial writes could commit while a mutation guard was evaluating; keep their financial consistency assertions.
- [ ] **Run green** on the race class, lifecycle cases, snapshot cases, and payment/conflict suites. Recheck serialization/deadlock errors are actionable rather than opaque 500s where conflicts are expected.
- [ ] **Run complete backend verification**: `./mvnw -B spotless:check verify`. Then run the combined HTTP S1/S2 probe on a disposable database and verify no mutation leak, zero-sum returned ledger and unchanged authorization. Run frontend regression/build before PR per repository policy even though this branch changes no UI.
- [ ] **Review and PR** only under implementation authorization; include exact race evidence and the stricter temporary leave policy. F02 starts only after this branch is integrated to `dev` and its API behavior is documented.

## F03 / Task 4 — Remove misleading account mutation

**Files:**

- Modify: `frontend/src/app/profile/profile.component.ts`, `.html`, and `.scss` only as needed.
- Modify: `frontend/src/app/core/auth/auth.service.ts`, `auth.service.spec.ts`.
- Create: `frontend/src/app/profile/profile.component.spec.ts`.
- Retain: `frontend/src/app/core/profile.service.ts`, `.spec.ts` account-owned preference behavior.

**Interfaces:**

- Until A01/A02, name and login email are server identity displayed read-only. Remove the unused local `AuthService.updateProfile` mutation after checking all call sites.
- Keep the existing `ProfileService.update` for account-scoped local preferences; exact scope copy: “Phone, avatar and currency preferences are saved on this device. Currency preferences do not change or convert recorded amounts.” Label the edit action “Edit device preferences.” Keep the honest “First seen here” date until server `createdAt` is exposed by A01.

- [ ] **Write rendered regressions** showing no editable login-email/name control, preference save retains only that account's data, the copy is visible, logout/login preserves authoritative auth response, and an A draft cannot save under B.
- [ ] **Run red** via `npm test -- --watch=false --include='src/app/profile/profile.component.spec.ts'` (Angular CLI/Vitest file selection; confirm supported option against the current runner). The existing editable account identity must fail the new expectations.
- [ ] **Implement** read-only account identity and the smaller preference form. Preserve appearance controls, profile navigation, validation already present and session reset behavior. No fake successful HTTP call or new endpoint in this branch.
- [ ] **Run green** on profile/auth/preferences specs, then rehearse login→profile→preference edit→logout→login on an intercepted or owned local account. Report whether backend calls were real or mocked. This is a truthful interim state; A01/A02 supply real mutation later.

## F03 / Task 5 — Visible detail/delete failures and deliberate retry

**Files:**

- Modify: `frontend/src/app/groups/group-detail.component.ts`, `.html`.
- Create/extend: `frontend/src/app/groups/group-detail.component.spec.ts` (check current tree for existing colocated tests before creating).
- Modify: `frontend/src/app/modals/new-expense-modal.component.ts`, `.html`, `.spec.ts`.
- Modify: `frontend/src/app/modals/add-member-modal.component.ts`, `.html`; create a colocated spec if absent and a separate failing load path actually exists.

**Interfaces:**

- Group detail exposes scoped `detailError`/`deleteError` states; user-triggered `retryDetail(): void` retries the current selected expense only. A failed delete keeps the row and any selection until successful server confirmation.
- Expense form provides `retryMembers(): void` for the currently owned group and edit identity. It preserves user-entered description/amount/date, reloads required membership/details, and does not submit while either is unavailable. Use current request generations and cancellation rather than a parallel ownership scheme.

- [ ] **Write failing rendered HTTP tests**: detail GET 503 displays an alert/retry; delete 409 displays the server's lifecycle guidance with the row intact; member GET 503 then explicit retry 200 enables the correct group's form without losing typed description/amount. No automatic retry loop or submit on retry.
- [ ] **Write ownership controls**: navigate A→B while A fails late; A's error/retry cannot appear or call A under B. Account change discards old errors/drafts according to current policy. If the add-member modal has no initial load failure path, document that and avoid inventing redundant retry state.
- [ ] **Run red** on the relevant group/detail and expense specs using supported `--include` filters. Confirm the silent existing callbacks fail before implementation.
- [ ] **Implement** explicit state/copy/actions using existing UI tokens, error formatting and scoped RxJS ownership. Preserve selected data on failure and show loading/unavailable distinctly from empty or settled.
- [ ] **Run green** on those suites and existing route-ownership regressions. Browser-check real or controlled 409/503 responses, retry, and correct group URLs at mobile and desktop widths.

## F03 / Task 6 — Pending modal behavior and accessible financial feedback

**Files:**

- Modify: `frontend/src/app/modals/modal-shell.component.ts`, `.html`, `.spec.ts`.
- Modify: `frontend/src/app/modals/modal.service.ts`, `modals.component.ts` as needed for one shared pending-close contract.
- Modify: `frontend/src/app/modals/new-expense-modal.component.*`, `settle-up-modal.component.*`, `new-group-modal.component.*`, `add-member-modal.component.*` only to register actual mutation-pending state and reset it.
- Modify: `frontend/src/app/groups/group-detail.component.html` for meaningful dates/labels if still hidden from assistive technology.

**Interfaces:**

- Add one close-policy state to the existing modal service: `mutationPending` read-only signal, `setMutationPending(boolean): void`, `requestClose(): void`. User Escape/backdrop/close controls call `requestClose`; pending writes keep the dialog open with “Saving. Please wait.” Session reset uses the existing forced `close()` cleanup regardless of pending state. Success/failure/destroy/account-change clears pending state; do not strand future dialogs.
- Disable only duplicate submit and user close during a dispatched mutation; keep read-only load/error dialogs closable. Never suggest closing cancels a server write. Durable retry after reload belongs to E02, not this UI patch.
- Settlement choices use full name plus email to disambiguate identical names. Running share totals have a polite live region; meaningful financial dates remain available to assistive technology.

- [ ] **Write failing tests** for submit→Escape/backdrop/close while pending (dialog stays), error→close allowed with retained draft, success→one close/refresh, and logout→forced close with late callbacks ignored. Test at least expense and payment paths through the shared shell; include group/member pending paths to catch leaked state.
- [ ] **Write accessibility regressions** for duplicate-name settlement choices, keyboard-visible retry, announced total updates, and a readable financial date. Preserve existing CDK focus restoration and inert background behavior.
- [ ] **Run red**, implement the smallest shared pending-close contract, then **run green** on modal-shell, settlement, expense, group and session specs. Avoid a second dialog state framework.
- [ ] **Run all frontend verification**: `npm test -- --watch=false`, full Prettier check and production build. Perform a browser keyboard/mobile rehearsal; real screen-reader/physical-device checks remain separately reported if unavailable. Run full backend checks before code PR per repository policy.
- [ ] **Review and PR** under implementation authorization. Include screenshots/error paths and precise limits: local preferences remain local; no account email change or currency conversion has been implemented.

## F04 / Task 7 — Existing behavior contract and integration reconciliation

**Files:**

- Modify: `openapi.yaml`, `docs/api/payment-retries.md`, `docs/security/auth-abuse.md`, `docs/security/session-lifecycle.md` only where current contract clarification is needed.
- Prepare/reconcile: `Agents.md` after application streams integrate.
- Source references: `dto/payment/RecordPaymentRequest.java`, `service/PaymentService.java`, `exception/GlobalExceptionHandler.java`, `config/SecurityConfig.java`, DTO null/default handling, split strategies, dashboard activity queries.

**Interfaces:**

- Describe optional UUID operation identity scoped to requester/group, identical original 201 replay, changed payload 409, and missing/null ID's additive behavior.
- Describe auth 429 and CORS-exposed `Retry-After`; distinguish domain error JSON from framework/security representations that differ.
- Document the existing expense-write conflict handler's transactional overlap limits. Do not advertise ETags/stale-form protection before E02 exists.
- Align nullable/omitted fields and bounds to actual request deserialization/validation. Baseline HTTP evidence confirms primitive `SplitInput.value` rejects both omitted and explicit-null JSON with 400; mark it required. Keep missing/null reference-field behavior aligned to the verified defaults.

- [ ] **Build a source-to-contract checklist** from audit section “Update SplitEasy's own OpenAPI now,” covering operation ID, 429, 409 scope, null/defaults/strict integer inputs, greedy wording, current feed ordering, headline-versus-net semantics and error schema limits.
- [ ] **Edit only existing behavior** and validate all internal OpenAPI references and YAML parsing using available tooling. No test suite needs to be invented for prose; if a semantic claim is uncertain, inspect its source/test and use a disposable authenticated request if necessary.
- [ ] **Check actual examples**: valid keyed payment replay, changed-intent conflict, auth limit headers, fractional integer input failure, omitted split values, and current activity ordering. Existing retained evidence/tests are acceptable if tied to unchanged code; state when verification is documentary.
- [ ] **After F01/F03 integration**, update member-removal/history-write 409 descriptions, complete historical-principal enumeration, read-vs-write isolation explanation, local profile restrictions and pending-close behavior. Correct the false “no signup-date column” statement to “`User.createdAt` exists but is not exposed in the current profile contract.” Do not mark future real profiles/currencies as shipped.
- [ ] **Run documentation verification**, `git diff --check` and inspect the final contract diff. Include the checklist in the PR. Application test runs are owned by the relevant implementation branches and combined integration gate; don't claim code passed tests that were not run.

## First-batch integration gate and next handoff

- [ ] Review F01's ledger/race evidence first; integrate in a way that preserves its complete change set. Integrate F03 independently. Reconcile F04 against the resulting behavior, not whichever branch happened to finish first.
- [ ] Run full combined backend/frontend formatting/tests/build and a disposable HTTP/browser rehearsal. Confirm S1/S2 fixed as defined; S4 remains explicitly assigned to F02, so do not claim all financial parity is complete.
- [ ] Record exact tested commit, test results and limitations; update memory with planning versus shipped work clearly distinguished. No schema migration, money reinterpretation or production mutation is expected in this batch.
- [ ] When execution is authorized and these dependencies are integrated, prepare the next batch: F02 (Sol high), A01 (Sol medium), E01 (Sol high). Read/expand their subsystem contracts against current `dev`, confirm exclusive ownership of shared DTOs and files, and create three fresh branches. Repeat until dependency-ready scope is exhausted or the user changes direction.

The user authorized execution of this plan on 19 October 2026. Complete the first batch through reviewed PRs to dev, then follow the parent roadmap and its integration/provider decision gates; no merge or deployment is implied.
