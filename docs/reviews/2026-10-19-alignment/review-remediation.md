# PR review comment remediation

The user authorized resolving review comments and merging PRs #95–#97 into dev on 2026-10-19. PR #95 must retain its roster retry fix; PR #96 must retain its departure safeguards and settings controls; PR #97 contains both implementations before its final contract is merged. PR #96 must merge before #97. No main/release/deployment step is part of this integration.

Implementation candidates: F03 `33e308fd3a9c7f9beaba1abef198560c0ad6c838`; F01 `6d416f0c529994d8fd6727ee75b68822bd5980ff` plus evidence `4147d82`; F04 contract `350518d2f5d1edc96a757b9416a42aebf1584d0d`. F01 integration head `e2730be34489e65e8f2c1db12c1ba5487abec834` includes the merged F03 dev revision. Contract integration retains both sets of agent conventions and the clarified zero-net wording; no application code conflict was present.

## Verification evidence

- The Add/Enter regression went red on an unexpected POST during a pending roster retry; shared eligibility gating passed the regression and successful/failed retry recovery. F03 full frontend214, formatting and production build passed; exact-head GitHub workflow39225024326 passed backend, Node22 frontend/build and container checks.
- New obligations HTTP regression went red on404; rendered settings regression went red on enabled removal of a net-zero intermediary. Backend230/230 passed with Java21, PostgreSQL16, Spotless and packaging. F01 frontend193/193 passed with Node22.23.3, full Prettier and production build.
- Combined F01/F03/F04 application tree: Node22.23.3 frontend220/220 and production build passed. Backend source is identical to the verified F01 candidate because F03/F04 do not change it.
- Independent packaged-JAR HTTP rehearsal passed88 requests in five scenarios, adding checks for both intermediary obligations, cancellation to an empty array, historical counterparties and former-member403 to the original financial/contract scenarios. JAR SHA256: `39ae67237ad5cdee37efa9889b02195a949007c62ca39a6ce803ffe89c417b73`. The owned application and database were removed. No production access.
- OpenAPI duplicate keys,134 internal references, official3.1 schema and81-row CSV/JSON passed. The38-package dependency graph is acyclic, with F04 depending on F01/F03.
- Required GitHub backend, frontend/build and container checks must pass for each final PR head before merge. Local evidence above is scoped to the recorded candidates; check current GitHub workflow results for the merge heads.

The sections below retain independent Matt Pocock review axes. These are source reviews, not additional test executions.

## Standards

### remediation-first-standards.md

Reviewed only the remediation deltas:

- F03: `git diff 20c6810a00032b74008dae9d85fb443dc216d0ed...33e308fd3a9c7f9beaba1abef198560c0ad6c838`; commit `33e308f fix(ui): serialize member roster retries and additions`.
- F04: `git diff 2c300ce8853bfdc4f1bd3912cf663126397bc0be...350518d`; commit `350518d docs: align bilateral removal contract and integration order`.

Both fixed points resolve and both diffs are nonempty. Sources: each branch's `Agents.md`, root/frontend `README.md`, and F04 `docs/agents/engineering-workflow.md`. No tracked `CODING_STANDARDS` or `CONTRIBUTING` files were found.

**Hard documented-standard findings: none.** F03 shares one admission predicate between the template and action, retains session cancellation/pending-write ownership, and exercises the user-visible retry/submit sequence with controlled HTTP. F04 describes integer-cent bilateral obligations, preserves member-only authorization and separates aggregate net from direct debts. Its companion endpoint is explicitly scheduled in PR #96, with #96 integrating before #97; absence from the documentation branch is not a violation.

**Material judgment-only smell findings: none.** All twelve baseline smells were considered against these hunks. Repeated test setup and the explicit group-ID guard do not warrant an abstraction or a material finding.

Verification boundary: F03 accurately records local Node 24 evidence. The required Node 22 checks are enforced by exact-head GitHub CI and remain a merge gate; their pending result is not classified as a source-standard violation. This review did not rerun tests or assert that CI passed.

Standards: **0 hard findings; 0 material judgment-only findings.**

### remediation-f01-standards.md

Reviewed `git diff b44d3b466c9e8a0af3d60bffb4236471ad1d5e9e...6d416f0c529994d8fd6727ee75b68822bd5980ff` in `alignment-f01`. Both refs resolve; the diff is nonempty (13 files). The corresponding log contains `6d416f0 fix(groups): gate departure on raw bilateral obligations`.

Sources: F01 `Agents.md`, root/frontend `README.md`, the previously inspected F04 `docs/agents/engineering-workflow.md`, and F04 OpenAPI at pinned `350518d`. F01 has no `docs/agents/` directory; no tracked `CODING_STANDARDS` or `CONTRIBUTING` files were found. The exact PR #96 review finding was inspected.

**Hard documented-standard findings: none.**

- `BalanceController`/`DirectObligation` retain the documented thin-controller, `@CurrentUserId`, DTO-record boundary. `PairwiseBalanceService.obligations()` authorizes membership first and owns a read-only `REPEATABLE_READ` transaction, matching `Agents.md` authorization/snapshot rules.
- The repository additions batch expense/payment pair aggregates. Historical identities resolve through the existing `FinancialParticipantService`, satisfying the documented history and no-N+1 requirements without granting former members access.
- Settings loads net balances and obligations together, clears financial conclusions while reloading, and applies the shared blocker to removal and self-leave, including an open confirmation. Counterparties display full names and emails; cents use the existing formatter.
- The implementation agrees with F04's contract: positive integer-cent `{from,to,amountCents}` debts, one direction per nonzero pair, opposing edges netted, historical counterparties retained, independent of simplification. The read does not replace transactional removal checks.

**Material judgment-only smell findings: none.** All twelve baseline smells were considered. The directional-pair record and shared aggregation helper serve the requested batch read; no speculative abstraction or material duplicated logic was identified.

This is a source review of the pinned application candidate. Full-test/format/build evidence remains the implementer's separate gate; no test or CI success is asserted here.

Standards: **0 hard findings; 0 material judgment-only findings.**

## Spec

### remediation-first-spec.md

No findings in the two reviewed remediation deltas.

Fixed points resolved and both three-dot diffs were nonempty:

- PR #95: `20c6810a00032b74008dae9d85fb443dc216d0ed...33e308fd3a9c7f9beaba1abef198560c0ad6c838`; commit `33e308f fix(ui): serialize member roster retries and additions`.
- PR #97/F04: `2c300ce8853bfdc4f1bd3912cf663126397bc0be...350518d2f5d1edc96a757b9416a42aebf1584d0d`; commit `350518d docs: align bilateral removal contract and integration order`.

PR #95 satisfies the exact review requirement, “Prevent additions while the retry is in flight, or invalidate/ignore that retry when submission begins.” The shared `canAdd()` gates both the button and `add()` used by Enter while roster loading; the existing retry guard prevents the opposite ordering during a POST. Tests exercise retained email, held GET plus both submission entry points, successful roster recovery, failed retry recovery, and later successful submission. No additional account/financial behavior is introduced.

F04 matches the approved foundation requirement, “After F01/F03 integration,” and the integration rule, “Reconcile F04 against the resulting behavior.” Its backlog now records both dependencies and the contract review explicitly requires #96 before #97. The new obligations contract describes one positive `{from,to,amountCents}` debt per pair, independent of simplification, historical counterparties without historical access, and transactional removal rechecking. It also corrects zero net to permit offsetting direct obligations, consistent with the design: “leaving/removing requires both zero net and no nonzero bilateral obligations.” No F02 projection implementation is claimed.

PR #96 implementation and its agreement with the new endpoint contract remain a separate review gate. The planned #96-before-#97 integration is required; absence of its companion code in the documentation branch is not itself a finding.

Spec total: 0 findings. Static review only; no tests rerun.

### remediation-f01-spec.md

No findings in `b44d3b466c9e8a0af3d60bffb4236471ad1d5e9e...6d416f0c529994d8fd6727ee75b68822bd5980ff` (PR #96 remediation). Both refs resolve; the diff is nonempty. Corresponding log contains `6d416f0 fix(groups): gate departure on raw bilateral obligations`.

The exact review asks to “Expose bilateral removal eligibility to the settings UI or otherwise update its gating to match this rule,” explaining that the UI must show “which obligations block it.” The new rendered settings rows show each counterparty, email, direction and amount. Net-zero members with incident debts display “even overall,” and shared eligibility checks disable both removal and self-leave, including an already-open confirmation. These checks do not depend on `simplifyDebts`. Loading/failure clears previous financial state and prevents a false settled/eligible result.

The approved containment rule is “leaving/removing requires both zero net and no nonzero bilateral obligations.” The endpoint and UI preserve that rule; existing removal still recomputes it transactionally. Settled non-creators remain eligible, and creator protection remains intact.

The new endpoint matches F04 `350518d`: positive `amountCents`, `from` owing `to`, one net debt per unordered pair, no simplification across people, historical users retained, no access gained from historical participation. Its whole-group aggregation is algebraically equivalent to the existing removal guard's per-user pairwise calculation: expense shares increase debtor→payer debt; recorded payments decrease payer→payee debt; reversed pairs cancel. Positive filtering omits zero edges and reverses overpayments correctly; self-edges are excluded. Membership authorization precedes ledger access, and REPEATABLE READ covers authorization, aggregates and participant resolution in one snapshot.

The approved design says, “Do not claim the initial fix completes all lifecycle parity.” This delta adds no canonical projection or F02 completion claim and makes the continuing bilateral restriction explicit.

Reviewed regression sources cover historical expense/payment counterparties, former-member 403 including nonexistent groups, opposite expenses, overpayment reversal, cancellation to zero, self-share omission, both UI modes, eligible controls, failed refresh, and route cancellation. Static review only; independent full test execution remains with the implementation/integration gate.

Spec total: 0 findings.
