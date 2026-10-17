# SplitEasy UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. No delegation or model change is authorized by this document itself. The user prohibits Astra models.

**Status:** Selected Pocket Objects implementation completed locally on17 October2026 after the user's explicit implementation request. The earlier broader task lists below are the proposal history; final scope rulings, executed work, regressions and limitations are authoritative in the [implementation ledger](../../reviews/2026-10-15-ui-redesign/implementation/ledger.md) and [verification](../../reviews/2026-10-15-ui-redesign/implementation/verification.md). The user subsequently authorized commit/push, successful branch CI, then a PR to dev. No merge or deployment is authorized.

**Goal:** Replace the full current UI with a distinctive, accessible, responsive and expressive visual/motion system while retaining every existing financial, account and group behavior.

**Architecture:** Keep Angular 21 standalone components/signals, current services and API contracts. Evolve shared SCSS tokens/primitives first, then shell/dialog behavior, then pages and form flows, then scoped motion. Motion never owns ledger or session state. Backend and schema remain unchanged.

**Tech Stack:** Existing Angular 21, SCSS, RxJS, CDK, Vitest. Selected implementation uses native CSS/canvas and existing CDK; no new runtime dependency or router screenshot transitions. Java 21/PostgreSQL suites remain regression gates.

**Spec:** [2026-10-15-ui-redesign-design.md](../specs/2026-10-15-ui-redesign-design.md). Read the [audit](../../reviews/2026-10-15-ui-redesign/audit.md), [research](../../reviews/2026-10-15-ui-redesign/research.md) and [four-way comparison](../../concepts/ui-redesign/README.md) too.

## Global constraints

- Start from this branch; fetch and inspect changes in `origin/dev` before execution, integrate deliberately without touching the original checkout. Feature PR targets `dev`; only a release PR targets `main`.
- Integer cents, deterministic payer remainder, all three split types, cap `1_000_000_000_000`, group authorization and derived repeatable-read balances stay intact.
- Account reset is synchronous. Preserve dashboard generation checks, interceptor session versions and complete-operation `takeUntil(session.changes)`. Animation callbacks never mutate financial/account state.
- Preserve full-replace PUT, hard-delete warning, editable partial/overpayment, group-owned simplify setting, route deep links and current theme precedence.
- One chosen system, shared `_tokens.scss` / `_ui.scss`, `.dc-amount` remains the money-status authority. No Material presentation, React migration, GSAP+Motion combination, remote runtime assets or scroll hijacking.
- OS reduced motion always wins; static visible content is the default fallback. All primary operations work through keyboard and touch.
- Web target design minimum 44×44 CSS px, visible focus; do not mislabel this project target as WCAG’s normative minimum. Text ≥4.5:1 except large text ≥3:1; meaningful non-text ≥3:1.
- Production motion addition ≤30kB gzip; initial JS/CSS growth ≤35kB gzip; critical fonts ≤120kB compressed. Actual bundle measurements decide acceptance.
- Reviewable checkpoints are proposed below. Commit/push only on the user’s subsequent explicit request.

## Review focus

1. A→B login, including same JWT value and 401 from A after B signs in: no old text, animation snapshot, modal draft or callback may appear as B. Owned by Tasks 2, 3, 8.
2. 100-character names, 200-character descriptions, CJK/RTL text, near-cap positive/negative values and repeated aggregate totals: no lost amount digits or document overflow at 320px/400% zoom. Owned by Tasks 1, 4, 5.
3. Rapid group navigation, row switching, modal close/reopen and reduce-motion changes mid-animation: latest state wins with no hidden/focusable ghosts. Owned by Tasks 2, 3, 5, 8.
4. Successful create-group followed by mixed add-member failures: show partial success and retry only failures, never duplicate group creation or completed membership writes. Owned by Task 7.
5. Net zero with offsetting debts, simplified and direct groups mixed, overpayment and percentage rounding: do not describe net zero as fully settled or silently change request semantics. Owned by Tasks 4, 6, 7.

## File ownership map

Existing files below are authoritative implementation locations, not suggested parallel copies.

| Area | Existing files to change | Focused additions only if needed |
|---|---|---|
| Tokens/type/primitives | `frontend/src/app/styles/_tokens.scss`, `_ui.scss`; `frontend/src/styles.scss`, `src/index.html` | `frontend/public/fonts/` + licenses; `frontend/src/app/shared/money.component.ts` and `.spec.ts` |
| Shell/navigation | `frontend/src/app/app.ts`, `app.html`, `app.scss`; `core/sidebar.service.ts`; `app.spec.ts` | Group tray component only if shell template becomes unwieldy |
| Accessible feedback/dialogs | `modals/modal-shell.component.{ts,html,scss}`, `modals/modals.component.ts`, `modals/modal.service.ts` | `shared/status-panel.component.ts`; `modals/modal-shell.component.spec.ts` |
| Dashboard | `dashboard/dashboard.component.{ts,html,scss}` | `dashboard/group-pass.component.ts` if reused; `dashboard/dashboard.component.spec.ts` |
| Group ledger/settings | `groups/group-detail.component.{ts,html,scss}`, `group-settings.component.{ts,html,scss}` | Matching `.spec.ts` files for state behavior |
| Expense | `modals/new-expense-modal.component.{ts,html}`, `modals/modals.scss`, `expenses/expense.service.ts` only for shared conversion/display reuse | `modals/new-expense-modal.component.spec.ts`; pure cents-based split-preview helper if necessary |
| Members/groups | `modals/new-group-modal.component.{ts,html}`, `add-member-modal.component.{ts,html}` | Component behavior specs |
| Settlement | `modals/settle-up-modal.component.{ts,html}`, `.spec.ts`; shared modal styles | Small confirmation/status primitive if multiple flows reuse it |
| Auth/profile | `auth/login.component.{ts,html}`, `register.component.{ts,html}`, `auth-hero.component.ts`, `auth.scss`; `profile/profile.component.{ts,html,scss}`, `core/theme.service.ts` | Auth field feedback specs; theme/profile tests |
| Motion | `frontend/package.json`, lockfile, `app.config.ts`, shared/component SCSS | `core/motion/motion-preference.service.ts`, `core/motion/reveal.directive.ts`, their specs; selected Silk Ribbon mouse/touch feedback |

## Task 0 — Select the art direction and finalize scope

**Depends on:** nothing. **Output:** one approved system and updated spec/plan, not product code.

- [x] Visual direction selected by user on16 October: Pocket Objects, Seafoam & Petrol default; optional Lilac & Plum and Peach & Cocoa, each with Light/Dark. Silk Ribbon mouse trail, corresponding touch bloom/trail, labelled icon dock and restrained liquid-glass hover/press. Other layouts remain alternatives.
- [ ] Review the written spec. Resolve optional group payment history, dirty-form confirmation and per-mode draft preservation explicitly. Base recommendation excludes full history and new profile/API work.
- [ ] Select an execution method and approve the resulting task plan. The design previews are not approval of the written technical spec by themselves.

## Task 1 — Foundation, money and stable layouts

**Depends on:** Task 0. **Files:** tokens, UI primitives, styles/index/fonts, new MoneyComponent and its tests.

**Interfaces:** `MoneyComponent` selector `app-money`, `amountCents = input.required<number>()`, `kind = input<'total'|'net'>('total')`, `size = input<'row'|'card'|'hero'>('row')`. Consumes integer cents and existing display helpers; outputs semantic text and `.dc-amount` modifiers. It does not convert currency or compute balances. Reuse a pure `formatMoneyCents(cents: number): string` with grouping and two fraction digits; signed net rendering is explicit and zero is unsigned.

- [ ] Add meaningful assertions: total `123456789`→`$1,234,567.89` with no status modifier; net `-1`→`−$0.01`; net `0`→`$0.00`; near-cap `999999999999` fully readable. Browser fixture asserts `scrollWidth <= clientWidth + 1` at 320px with 100-char group name and 200-char description.
- [ ] Run `npm test -- --watch=false`; new behavior assertions should fail before implementation. Do not create unit tests that merely duplicate SCSS constants.
- [ ] Implement approved palette/type/geometry as tokens and evolve existing primitives. Preserve old selectors while migrating screens. Add WOFF2 fonts and license files; no Google font network request at runtime. Body 16/24; controls ≥44px; explicit focus tokens. Money thresholds use static long-value classes, not measuring on every render.
- [ ] Re-run tests and production build. Verify contrast, font fallback/swap, negative/zero/large amounts at 320/390/768/1440 widths. Save visual evidence.
- [ ] Record a review checkpoint. Commit only if subsequently requested.

## Task 2 — Motion preference and ownership contract

**Depends on:** Task 1. **Files:** new `core/motion/motion-preference.service.ts`, tests; shared CSS. Motion installation can wait until Task 8.

**Interfaces:** `MotionPreferenceService.reduced: Signal<boolean>` and `setReduced(value: boolean): void`; effective reduction is OS reduce OR device choice. `data-motion='reduced'` supports CSS. Consumers register their own destroy/session disposers; no generic global timeline registry is needed.

- [ ] Add tests `osReductionAlwaysWins`, `changingPreferenceNotifiesConsumers`, `destroyRemovesMediaListener`, `sessionChangeCancelsOwnedEffects`. Assert no stale completion callback changes state after disposal.
- [ ] Run the focused tests, then implement `matchMedia` subscription, local device preference and final-state cancellation. Persist only the appearance choice, never user/financial data.
- [ ] Verify live preference changes, OS fallback, hidden-tab stop and JS/setup failure. Content must remain visible with no animation runtime. Test account change mid-effect with the existing session-isolation harness.
- [ ] Review the ownership contract before adding page choreography.

## Task 3 — Navigation and modal accessibility

**Depends on:** Tasks 1–2. **Files:** shell/sidebar; ModalShell and its tests; ModalService/ModalsComponent only for presentation lifecycle.

**Interfaces:** Keep `ModalService` methods/active state and existing projected shell API `heading`, `maxWidth`, `(closed)`. Extend presentation with optional initial-focus target and variant `compact|expense`, without moving HTTP logic into shell. `SidebarService` remains presentation-only; a group tray replaces the old wide/rail layout for the selected Pocket Objects shell.

- [ ] Add regression assertions: at 390px, Profile and groups are reachable; dialog has heading-based accessible name; initial focus is inside; Tab/Shift+Tab stay inside; Escape closes topmost; closing restores launcher; missing launcher focuses main. Session reset closes synchronously without dirty-draft prompt or exit delay.
- [ ] Run targeted specs before changes. Implement CDK focus trap, inert background/managed scroll lock, labelled controls, skip link, active navigation and group tray. Guard focus restoration if the route/session changed.
- [ ] Browser check: virtual keyboard, long modal, sticky footer, landscape 844×390, 200%/400% zoom. No background scrolling/focus escape; no action hidden behind bottom navigation. Verify route deep links and unknown/legacy redirect behavior unchanged.
- [ ] Record a review checkpoint with desktop/mobile screenshots and keyboard steps.

## Task 4 — Dashboard, groups and activity hierarchy

**Depends on:** Tasks 1–3. **Files:** dashboard component, optional GroupPass; dashboard tests. DashboardService logic stays unchanged unless a regression test proves a required presentation-state gap.

**Interfaces:** Consume existing `DashboardResponse`, shared `DashboardService.data/loading`, existing refresh ownership and modal launch methods. No additional API fan-out for charts.

- [ ] Add tests: no-groups state launches Create group; `totalNetCents === 0` with owed/owe nonzero shows both directions and is not called all settled; groups format net/total correctly; a payment uses neutral amount semantics; refresh failure retains trusted session data with retry; reset clears all data.
- [ ] Implement chosen balance scene/group presentation, activity/People, scope labels and CTA hierarchy. Remove repeated Members/Open links to the same destination. Reserve loading dimensions; use explicit empty/error/status components.
- [ ] Verify 0/1/25 groups, long names, positive/negative/zero/near-cap values, empty feed/people, stale refresh and both themes at all representative widths. Source data still comes from one cached dashboard call; no fabricated trends.
- [ ] Record a review checkpoint.

## Task 5 — Group detail, receipt disclosure and settings

**Depends on:** Tasks 1–4. **Files:** group detail/settings components + tests; ModalShell for destructive confirmation reuse only.

**Interfaces:** Existing `GroupService`, `BalanceService`, `ExpenseService`, route params and `SettlePrefill` semantics. Latest group ID/expense ID owns the displayed response. Mutation completion calls `dashboard.refresh()` as today.

- [ ] Add tests for A→B rapid group navigation; expand expense A then B before A detail returns; edit an expanded expense and assert refreshed amount/shares agree; detail/deletion failure shows recovery. Assert creator cannot leave/delete is creator-only; unsettled remove disabled but server409 still displayed; zero balance can remove/leave under current rules.
- [ ] Run focused specs, implement header/actions, balance summary, month ledger and receipt. Keep `@if`/tracked UUID rows; no new data aggregation. Clear/refetch the currently displayed detail after successful edit.
- [ ] Implement named simplify switch, inline rename states and explicit destructive confirmation. Do not promise undo for hard delete. Preserve server authz and 403-before-404 privacy.
- [ ] Verify empty/forbidden/loading/detail-error states, many members/long names, rapid navigation and settings response failure on mobile/desktop. Check focus after remove/delete/cancel.
- [ ] Record a review checkpoint.

## Task 6 — Expense composer and allocation feedback

**Depends on:** Tasks 1–3, 5. **Files:** expense modal/template/styles + behavior specs; pure split-preview helper only if shared production logic is warranted.

**Interfaces:** Preserve `CreateExpenseRequest`, `dollarsToCents`, `percentToBasisPoints`, split mode enum, group/member APIs and entire-operation session cancellation. Preview must calculate from the **same converted integer payload** used for save; it does not replace backend validation.

- [ ] Add tests covering `10.01/3` payer remainder, excluded payer, zero shares, exact sum mismatch, 33.335/33.335/33.33 percentage conversion, full/zero percentages, max amount and over-cap rejection, read failure retaining form, saving prevents duplicate submission, edit prefill and account reset. New member inputs must have full member-specific accessible names.
- [ ] Red/green focused specs, then implement desktop two-zone/mobile sequential composition, labelled scope/payer/category controls, live assigned-total text/bar and success/error states. Keep modal `(submit)="$event.preventDefault(); save()"` with bare controls.
- [ ] Confirm edit semantics from persisted cents; do not invent recoverable historical percentages. If per-mode draft preservation was approved, keep it modal-local and discard on close/session reset.
- [ ] Browser check all three modes, 1/3/12 participants, negative/zero/large totals, alternate payer/date/category, exact allocation, rejected POST/PUT leaves ledger unchanged. Keyboard controls produce the same payload as touch.
- [ ] Record a review checkpoint.

## Task 7 — New group, members and settlement feedback

**Depends on:** Tasks 1–3, 6. **Files:** new group/add member/settlement components and tests, shared modal styles. `PaymentService` request semantics unchanged.

**Interfaces:** Existing group create and sequential add-member operations. Expose presentation result `{group, addedEmails, failedEmails}` locally after creation; retry operates only on failed emails against the already-created group. Settlement consumes existing direct/simplified rows and scoped prefill.

- [ ] Add tests: create succeeds then one membership fails; retry never calls create again or repeats successful members; session change cancels queued additions. Settlement tests cover mixed settings, fallback notice, positive direction versus payer/payee, partial payment, overpayment sign flip, amount<=0, cap, duplicate click, failed request never emits success.
- [ ] Implement truthful partial-success feedback, named type radios/email chips, clear existing-account language. Add member load/error/retry and retain typed email.
- [ ] Implement direct/simplified contextual copy, recipient/group prominence, inline errors and accessible receipt confirmation. Authoritative response refreshes data immediately; animation only decorates resulting state. Use “Suggested transfers” rather than “fewest payments.”
- [ ] Verify actual HTTP flows against disposable fixtures; no production write. Check Back/Escape/reopen and account switch while requests and confirmations are pending.
- [ ] Record a review checkpoint.

## Task 8 — Auth, profile and scoped motion choreography

**Depends on:** Tasks 1–7. **Files:** auth/profile/theme, new reveal directive, app.config, package/lock, component styles. Add only `motion@14.3.0` when this stage is authorized.

**Interfaces:** Existing AuthService/SessionService and ThemeService remain authority. `RevealDirective` consumes an element and optional once-only delay capped at140ms; it returns no business callback. Each scope owns Motion controls/disposers and checks effective reduction. Optional `withViewTransitions` is feature-gated, skips auth/session and does not wait for HTTP to finish indefinitely.

- [ ] Add meaningful behavior tests: failed auth fields/errors/submit; current autocomplete/password toggle; explicit theme sets native color-scheme; motion canceled on destroy, account change, preference change and rapid route replacement; unsupported View Transitions route normally. Assert callbacks after teardown cannot revive user content.
- [ ] Implement auth composition, profile/local-storage honesty, motion setting and type hierarchy. Preserve actual backend limitations; no fabricated signup date, profile endpoint or FX conversion.
- [ ] Add the approved motion matrix in order: press/focus, dialog entry/exit, receipt disclosure, split-bar redistribution, group pass→route, once-only scene, scroll seam and local settlement stamp. Numbers show final values immediately. Disable/pause decorative work offscreen/hidden.
- [ ] Record production bundle diff, font transfer, 10s trace frame durations/long tasks and input latency under documented throttling. Reduce layers if over budget; do not grow the dependency stack. Test interruption and keyboard/reduced-motion paths in actual browsers, not only jsdom.
- [ ] Record a review checkpoint with motion video if tooling allows; screenshots alone cannot prove animation quality.

## Task 9 — Whole-app verification and delivery preparation

**Depends on:** Tasks 1–8. **Files:** only corrections justified by verification, evidence/docs and Agents.md living memory.

- [ ] Run full frontend: Node22 `npm test -- --watch=false`; `npx prettier --check 'src/**/*.{ts,html,scss,json}'`; `npm run build`. Baseline in this audit: 80 tests/14 files pass. No lint script exists; do not report lint passed by inventing one.
- [ ] Run full backend: Java21 and Docker, `cd backend && ./mvnw test`. Preserve ledger snapshot, security and overflow regression suites. Source remains unchanged unless a separately approved bug is exposed.
- [ ] Run existing deployment regressions `python3 -m unittest discover -s deploy/tests -p 'test_*.py'` (current files: test_delivery.py, test_release.py, test_stack.py); ShellCheck/Actionlint where already configured. Do not deploy or use disposable Compose overrides on production.
- [ ] Browser matrix: desktop1440×1000 and1280×900, tablet768×1024, mobile390×844 and320×740, landscape844×390. Both themes on all main surfaces; explicit/system theme; OS reduced motion; keyboard-only full flows; focus and screen reader smoke on representative dialog/ledger. Save failures as well as corrected evidence.
- [ ] Exercise login/register, empty/populated dashboard, group detail/receipt/edit/delete, settings rename/simplify/member guards, new group with partial membership failure, all split modes, settlement direct/simplified/partial/overpay/history feed, profile/local preference disclosure, logout/expiry and A→B session ownership. Use disposable data only.
- [ ] Measure production build, contrast pairs, stable full amounts at cap, scroll widths and animation/input traces. Document actual browser/device/throttling and raw results. No performance or accessibility certification by screenshot alone.
- [ ] Self-review branch against spec; resolve all material failures. Provide user review with before/after screenshots, build/test results, remaining limitations and a proposed PR description including end-to-end Testing evidence. Only commit/push/open PR when asked. Do not merge or deploy.

## Optional follow-ons, outside the base plan

- **Group payment history:** use `PaymentService.listPayments(groupId)`, distinguish complete history from capped recent dashboard feed; add read ownership/loading/error tests. No new API is needed. Approve page/panel scope separately.
- **Cursor:** Silk Ribbon is selected; retain reference alternatives in [cursor-options.md](../../reviews/2026-10-15-ui-redesign/cursor-options.md). Native SVG pointer + optional follower can reuse the motion policy. Budget ≤3kB gzip custom code, no cursor package. Test fine/coarse pointers, text selection, focus, forced colors, reduced motion and teardown.
- **Profile ownership/persistence:** global client preference key and local name/email limitations are separate correctness/product work; do not imply visual polish fixes them.

## Verification of this planning run

This run verifies unchanged-app frontend80/80 and production build; backend boots against a disposable migrated PostgreSQL database; broad current-UI browser sampling and concept-specific checks are documented separately. The future checks above are **unchecked acceptance work**, not claims completed now. No full backend suite was rerun solely for docs/prototype creation.

## Plan self-review

All existing surfaces map to Tasks3–8. Money/overflow, session interruption, partial success, mixed simplification and zero-with-offsetting-debts have explicit behavioral tests. Optional features are separate. Shared interfaces match the existing application and this spec; the new components/services are intentionally small. Review/execution approval remains outstanding.


## Selected-design refinement — 16 October 2026

The visual choice is settled. Update the existing theme foundation to track **color theme** independently from **mode**, defaulting to Seafoam & Petrol while preserving the current System/Light/Dark product preference. Provide Appearance access in header/Profile. Scope any new browser preference keys so they cannot overwrite unrelated account state. Carry Pocket Objects typography, silhouettes and balance/group/people composition through the implementation tasks above.

Shell acceptance adds four SVG-and-label dock controls at mobile/tablet widths through1024px, one active indicator, Expense selected only during composition, and focus/selection restoration on dismissal. Cursor acceptance adds the selected Silk Ribbon with a native precise tip, a clean touch bloom/trace that respects scroll/pinch, and stop/reset handling. Glass acceptance adds bounded hover sheen/press feedback, readable surfaces, reduced-motion/forced-color fallbacks and actual mobile/tablet performance checks. No new package is justified by these refinements. The static prototype implements the visual exploration; Angular integration is complete in the selected scope; see the implementation ledger.
