# F02 canonical obligations

Base: `337858d8197d9228d80a03dfcc7d3aef654b4e04` (`dev`, after F01/F03/F04).
Branch: `bugfix/alignment-f02-obligation-projection`. Approved scope: roadmap F02 and design sections 1–2. Implementation uses the selected Matt Pocock TDD/interface workflow and Ponytail; independent Standards/Spec reviews and publication remain with the root worker.

## Behavior and interfaces

`ObligationProjectionService` selects direct pair netting or deterministic greedy net matching from the complete financial snapshot. `NetSettlementCalculator` holds the pure matching algorithm; ties in both queues and final output use UUIDs. There is no projection/simplification dependency cycle. `BalanceService` and stored expenses/payments are unchanged.

`/balances/mine`, dashboard cards/headlines, People and settlement actions share that projection. Settings uses the additive member-only `/balances/current-obligations`, with the existing directed `{from,to,amountCents}` shape. The old `/balances/obligations` remains raw and independent of mode, preserving its contract. Positive viewer balances mean the other person owes the viewer; directed debts mean `from` owes `to`.

Simplified A→B→C 1000 projects A→C 1000, omitting zero-net B. Recording A→C clears all simplified surfaces while the raw three-edge cycle remains. Direct mode displays the cycle as both owed and owing, and blocks departure until its incident debts are settled. Simplified zero-net intermediaries can depart; creators remain protected. The F01 old-expense inactive-participant edit/delete guard remains unchanged.

Mode PATCHes with a non-null `simplifyDebts` use the existing group-row guard and READ COMMITTED, including membership reauthorization after waiting. Target obligations involving former participants return 409 with explicit re-add/repair guidance, and roll back accompanying settings edits. Null/omitted-mode PATCHes retain dirty-column updates; stale non-mode requests cannot overwrite an omitted mode. The old mode-last stale-read test cases now run after the first edit because locking prevents that old ordering; opposite cases still exercise stale name/type writes after a real concurrent mode commit. Added PostgreSQL lock-wait tests cover the new serialization behavior directly.

The net-derived `/debt-simplification` preview remains available in either mode. Its additive nullable `warning` explains in direct mode that net-derived transfers may leave offsetting direct obligations. The settlement modal consumes canonical dashboard rows directly, removing its second preview fetch/fallback and obsolete savings claim. It shows direct-mode explanation and never infers generic “all settled” from an empty net preview.

## Regression evidence

Authorized seams are authenticated HTTP through the real application/PostgreSQL and rendered Angular with HTTP boundary responses. SQL only prepares a historical missing-membership fixture and opposing display-name order for UUID ties; expected financial outcomes are asserted through HTTP. Existing snapshot/race probes coordinate actual transactions and observe PostgreSQL blocking, without replacing financial query results or synchronizing with sleeps.

Actual command logs are outside git under `/workspace/issue-context/alignment-implementation/batch2/F02/`.

| Scenario | Red | Green |
| --- | --- | --- |
| S4 simplified chain counterparty and paid state | `s4-red.log`: `/mine` returned B instead of C | `s4-green.log`: A→C, B omitted; after payment every dashboard/People/action zero, raw cycle retained |
| Departed intermediary mode exposure | `mode-red.log`: mode PATCH returned 200 | `mode-green.log`: simplified departure 204; direct toggle 409, rejected rename/history unchanged, former access 403 |
| Canonical settings eligibility | `settings-red.log`: Remove/Leave Bob disabled despite canonical eligibility | `settings-green.log`: enabled and actual DELETE sent; direct and creator blockers retained |
| One canonical settlement action | `modal-red.log`: an independent net-preview request was issued | `modal-green.log`: rendered/posting A→C uses dashboard projection |
| Direct cycle and net preview warning | `direct-red.log`: warning absent | `direct-green.log`: raw cycle remains actionable; warning explains empty preview; accepted toggles preserve raw projection |
| UUID ties independent of names | `ties-red.log`: restored original amount-only comparator chose the wrong first debtor | `projection-races-green.log`: UUID ordering and counterparties agree in preview/current/mine/dashboard |
| Mode/removal/financial concurrency | Real independent connections wait on PostgreSQL row locks | `projection-races-green.log`: both intermediary-removal orders, expense/payment before mode, departed requester mode 403; existing history/snapshot/PATCH suites pass |

The focused backend run passed 56 tests. Final verification passed on the final source:

- Backend, from `backend/`: `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` — **240 tests, zero failures/errors; BUILD SUCCESS**, production JAR packaged (`backend-final.log`). Includes the final damaged-history exposure and exact accepted-toggle expense/payment preservation assertions.
- Frontend, from `frontend/`: `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false` — **220 tests across 32 files**, all pass (`frontend-final.log`).
- Same runtime: `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build --configuration production` — passes without warnings (`frontend-build.log`).
- Full-source formatting: `npm exec --yes --package=node@22 -- node node_modules/prettier/bin/prettier.cjs --check 'src/**/*.{ts,html,scss,json}'` — all matched files pass (`prettier-check.log`).
- Contract: `python3 /workspace/issue-context/alignment-implementation/F04/verify_contract.py /workspace/spliteasy-worktrees/alignment-f02` — duplicate-key parse, official OAS 3.1 schema, **139 internal refs**, backlog/rollout metadata pass (`openapi-validation.log`).
- `git diff --check` passes. No migration or dependency changes.

The frontend total retains the baseline 220 count: three new rendered/HTTP cases replace three obsolete second-preview-specific cases. Existing payment identity/replay, session ownership, creator, history, authorization and snapshot/race tests remain green.

## Limits

No schema, currency, expense split/editor, authentication/profile, or dependency change. Legacy amounts retain their existing units; C01 owns denomination/backfill. History is neither rewritten nor automatically repaired. No claim of globally minimum payment count. No production traffic, merge or deployment. Browser and independently packaged HTTP rehearsal are separate root-owned follow-ups; the implementation's UI evidence is rendered Angular, not a real-browser claim.
