# Splitwise Alignment Implementation Plan

> **For agentic workers:** Follow the [Matt Pocock skill workflow](../../agents/engineering-workflow.md), using `tdd`, `diagnosing-bugs`, `code-review`, and `pr` where applicable. Preserve the user's three-thread limit and `dev` branch/PR workflow. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the audited financial correctness and Splitwise core gaps, then deliver selected Pro workflows with traceable acceptance evidence.

**Architecture:** Retain the modular Spring/Angular application and derived ledger. Separate access membership from financial identity, standardize obligation projection, then add currency, durable write semantics, social scope, recoverable history, and collaboration. Build optional integrations only after their dependencies and product decisions are satisfied.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL 16, Flyway, Angular 21 signals, Vitest, Maven, CI Node 22.

**Spec:** [Proposed design](../specs/2026-10-19-splitwise-alignment-design.md).

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

1. Removed people must remain financially visible without regaining access: F01 exercises old damaged data, S1/S2, and authorization.
2. Simplification must not claim settlement while other screens show actionable debt: F02 exercises S4, both modes, and setting changes.
3. Browser retries, stale forms, account switches, and delayed callbacks must not duplicate or misattribute money: F03/E02/H07 pin these cases.
4. Legacy denomination and 0/3-decimal currencies must not reinterpret or mix money: C01 exercises migration and every balance consumer.
5. Identity claims, restores, recurrence, and composite settlement must remain atomic under races: G01/H01/H04/B02 use PostgreSQL transaction tests, not mocks alone.

## What to implement first

**Batch 1 is exactly three branches:** F01 financial containment (`gpt-6.1-sol`, high), F03 honest profile controls and remaining interaction failures (`gpt-6.1-sol`, medium), F04 contract corrections (`gpt-6-luna`, medium). The [foundation execution plan](2026-10-19-splitwise-alignment-foundation.md) defines their files, interfaces, regression cases, and gates.

F02 obligation coherence depends on F01 and must start from its integrated result. It is deliberately not a fourth concurrent ledger thread. The next three candidates are F02, A01 server-owned profile, and E01 split intent; they can run together after ownership review because they primarily change balance projection, account/profile, and expense/split code respectively. Rebase all three on the same reviewed foundation.

These are planning IDs, not GitHub issue numbers. Implementation was requested on 19 October 2026; execute the first batch and preserve the documented dependency gates. Subsequent batches refill available slots only with dependency-ready tasks; there is no requirement to keep three agents busy when the safe dependency graph exposes fewer.

## Milestones and gates

| Milestone | Deliverables | Exit gate |
| --- | --- | --- |
| M0: Financial trust | F01, F02, F03, F04 | S1/S2/S4 regressions green; no hidden former-member debt; honest controls; mode-consistent settlement; current API contract. |
| M1: Foundations | A01, A02, A03, E01, E02, C01, H06 | Real profiles/recovery/sessions, durable split intent and writes, denominated per-currency ledger, focus/navigation freshness. Owner confirmed legacy USD on 19 October 2026; C01 implements the backfill. |
| M2: Core social and corrections | H01, G01, G02, G03, E03, B01, B02 | Friends/invitations/non-group flows, multi-payer entries, payment correction and cross-group settlement, recovery/deletion policies. |
| M3: Core collaboration and history | H02, H03, H04, H05, C02, A04 | Comments/receipts, notifications/reminders, recurrence, bounded history/export, account closure after export, second locale verified. |
| M4: Web mobility | H07 | Account-safe installability, offline reads, queued writes and conflict recovery; real offline/reconnect browser rehearsal. |
| M5: Pro workflows | P01, P04, P05, P07, then P02 and P03 | Search/analytics/saved rules/JSON backup, followed by reviewed OCR/itemization and FX; no automatic posting from OCR. |
| Conditional ecosystem | A05, B03, H08, H09, P06, P08, P09 | Separate platform/provider/business design approved and integration-specific evidence produced. |

Milestones are exit criteria, not blanket parallel batches. A ready package from a later milestone may proceed if it does not consume an unbuilt contract or conflict with an active owner. Finish a safe core vertical slice before starting a broad Pro package. Real-money integrations and native apps remain part of full advertised-product parity, even when intentionally deferred from web-core delivery.

## Work-package contracts

Size: **S** = bounded existing behavior; **M** = one principal workflow; **L** = cross-layer or financial migration; **XL** = several reviewable vertical slices. These are relative sizes, not dates. XL packages must be expanded into a linked subsystem execution plan before coding. New plan contracts supersede proposed names here only through an explicit documented revision.

Models: `Sol H` = `gpt-6.1-sol` high; `Sol M` = `gpt-6.1-sol` medium; `Luna M` = `gpt-6-luna` medium. Use the high tier for financial algorithms, migrations, security, and identity races; medium for bounded UI/data workflows; Luna for contract/documentation work. No Astra and no Sol reasoning above high.

### M0 — financial trust

**F01 — Preserve financial principals and contain unsafe lifecycle mutations.** L, Sol H; depends on baseline only. Modify `BalanceService`, `PairwiseBalanceService`, `GroupService`, `ExpenseService`, `PaymentService`, membership/group/user repositories; add a group-row write guard and historical-principal read service. Exact first-batch plan linked above. Acceptance: intermediary removal conflicts, settled departure remains possible, deleting departed-party history conflicts, old damage is fully visible and zero-sum, former-member access remains 403, and write/removal races cannot create obligations after departure. No schema migration needed. This package contains the defects; F02 supplies the final simplified-mode policy.

**F02 — Canonical direct/simplified obligations.** L, Sol H; depends F01. Add `service/ObligationProjectionService.java`; extract the pure net-matching function from `DebtSimplificationService` into `service/NetSettlementCalculator.java` so it does not call back into projection. Modify `PairwiseBalanceService`, `DashboardService`, `DebtSimplificationService`, and the leave guard. Proposed internal record: `Obligation(UUID payerId, UUID payeeId, long amountCents)` using the existing implicit legacy unit, without asserting USD. Add `forGroup(UUID requesterId, UUID groupId)` as the authorized projection entry point and a package-internal snapshot path for already-authorized dashboard aggregation. Do not create a projection↔simplification service cycle. Acceptance: S4 simplified A→C payment yields zero everywhere; direct cycles remain explicitly direct; setting toggles preserve raw data and reject newly actionable former-member exposure. Test stable UUID ties, zero input, reversed pairs, equal-net intermediaries, and all DTO signs. C01 replaces this internal amount contract with explicit minor units and required currency throughout the projection.

**F03 — Honest local controls and actionable interactions.** M, Sol M; depends baseline. Limit profile identity changes to read-only until A01/A02; explain that local currency preference does not denominate or convert transactions. Repair silent expense-detail/delete failures, member-load retry, duplicate-name settlement labels, and close-during-save uncertainty. Preserve existing draft/account ownership. Acceptance: rendered tests and a keyboard/browser pass prove errors are visible, retry is deliberate, drafts survive failure, and no success is shown for a local-only login-email change. Exact file ownership and first-batch cases below.

**F04 — Correct the current API and project memory.** S, Luna M; depends baseline. Update `openapi.yaml` and API docs for existing operation IDs, 201 replays/409 changed intent, 429/Retry-After, limited conflict semantics, null/omission/integer behavior, greedy wording, error representations, and activity ordering. Correct `Agents.md`'s existing registration-date and financial-lifecycle claims as a joint integration edit. Acceptance: each documented fact traces to a current DTO/controller/test; no planned endpoint is advertised as shipped. After F01/F03 integrate, the integration owner updates their changed behavior before closing M0.

### M1 — account, money, and write foundations

**A01 — Server-owned current profile and preferences.** M, Sol M; depends F03/F04. Add `controller/ProfileController.java`, `service/ProfileService.java`, DTOs under `dto/profile/`, and `GET/PATCH /api/me`; extend `User` with phone/contact preference, avatar choice, preferred currency, and locale only as used. Expose existing `createdAt`. Modify frontend `core/profile.service.ts`, `core/auth/auth.service.ts`, and `profile/profile.component.*`. Do not allow email mutation here. Acceptance: name/preferences survive login/new browser, revoked account requests cannot update a new session's UI, account A data never appears under B, invalid preferences reject without partial writes. Local preference import is account-owned and explicit; never import ownerless legacy data.

**A02 — Verified identity, recovery, and password/email changes.** L, Sol H; depends A01; SMTP/sender setup is an execution dependency. Add token persistence/service and public recovery/verification plus authenticated change-password/change-email routes under `controller/AuthController.java` / `service/AuthService.java`. Store only token hashes; use expiry, single use, generic recovery responses, throttling, and transactional email uniqueness. Proposed commands: `POST /api/auth/password-reset-requests`, `POST /api/auth/password-resets`, `POST /api/me/password`, `POST /api/me/email-change-requests`, and token confirmation routes documented in its subplan. Acceptance: expired/replayed/wrong-purpose tokens fail, concurrent claims have one winner, old email remains login until confirmation, BCrypt byte limits remain intact, and logs reveal no tokens. Real delivery requires an explicitly configured sender; no email is sent during planning.

**A03 — Revocable session lifecycle.** L, Sol H; depends A02/E02; cannot share auth/session files with an active A02 branch. Replace the deliberate access-token-only contract with a reviewed session design: one-hour access credentials, server-owned revocation state, optional rotating refresh credentials in an HttpOnly secure cookie with trusted-origin/CSRF protection, and device session list/revoke. Specify per-request revocation enforcement so all-device logout takes effect immediately rather than only at JWT expiry. Acceptance: refresh reuse, password reset, logout-all, one-device logout, expired access, cross-origin requests, and tab/account switches behave as documented. Retain current login/register limits and plan shared enforcement before adding instances.

**E01 — Persist split intent; add weights and adjustments.** L, Sol H; depends F01. Modify `Expense`, `ExpenseParticipant`, `CreateExpenseRequest`, `ExpenseResponse`, `SplitType`, `service/split/*`, and `new-expense-modal.component.*`; add only required Flyway columns. Persist typed original inputs plus final shares; define weights as positive integers and adjustments as signed minor-unit deviations whose final shares stay nonnegative and sum to total. Original 50/50 percentages on a one-unit expense must survive description editing. Legacy percentages remain explicitly exact because their original ratios are unknown. Acceptance: create/edit round-trips intent, changed amount recomputes deterministically, remainder policy is preserved, and adversarial weights/adjustments overflow or invalid totals reject atomically.

**E02 — Retry-safe creation and stale financial edits.** L, Sol H; depends E01/F04. Extend the V9 pattern to expense/group creates with scoped operation IDs; introduce resource versions for financial edit/delete, require the expected version on new clients, and document compatibility rollout. Persist account-owned uncertain operation identities across reload before offline queue work. Keep same-field group PATCH policy unless explicitly changed; do not apply full-resource overwrites to settings. Acceptance: lost responses/reloads/raced duplicate POSTs write once; changed intent conflicts; stale sequential GET→PUT is 409 and preserves the winner; old-session responses cannot commit to current UI; old clients have a time-bounded migration path before version enforcement.

**C01 — Denominated multi-currency ledger and precision.** XL, Sol H; depends F02/A01/E01/E02. The owner confirmed all legacy amounts are USD on 19 October 2026; C01 owns the still-pending backfill and currency implementation. Serial slices: (1) catalogue and additive currency fields with existing behavior intact; (2) verified legacy backfill and per-currency aggregation/payment/leave invariants; (3) explicit minor-unit API/client forms and all money formatting; (4) retire compatibility fields after deployed-client verification. Modify all money entities/DTOs, repositories, balance/payment/expense/dashboard services, `shared/money.component.ts`, and financial forms. No two agents independently rewrite the money DTOs. Acceptance: USD/EUR remain separate, JPY/KWD input is exact, 1,000 USD plus 1,000 JPY never becomes 2,000 of either currency, settling one currency leaves the other intact, and profile preference changes no history. Verify old/new application compatibility and a production-like migration rehearsal before promotion.

**H06 — Predictable cross-device freshness.** M, Sol M; depends F03. Modify existing dashboard/group data services and route/focus hooks; revalidate on navigation, after writes, and on returning to the visible tab with bounded request coalescing. No WebSocket dependency initially. Acceptance: another client changes an expense, focused/navigated client refreshes it, failures show stale/unavailable state, older responses never replace newer data, account switches cancel previous work. This is not offline support or instant push synchronization.

### M2 — social workflows and recoverable money

**H01 — Audit, versions, expense delete/restore.** L, Sol H; depends F01/E02/C01. Add transactional change records with actor, timestamp, resource version, before/after financial data and operation identity; add expense `deletedAt` and restore route with current version. Update aggregate queries to exclude deleted entries but preserve audit rows. Record actual recorder independently of financial payer. Acceptance: edit/delete/restore each has attributable history, restore changes balances once, restoration with inactive people or conflicting obligations is explicitly blocked or reviewed, and authorization/conflict failure leaves both ledger and audit unchanged. Existing history is tagged imported/unknown actor, never invented.

**G01 — Participant identity, invitations, and safe claims.** XL, Sol H; depends A02/C01/H01. Serial slices: map existing users to stable financial participants without changing amounts; permit unregistered participant records; add expiring email invitations and acceptance; add revocable group links; then phone contact invitations only with a selected verified channel. Proposed tables `participants`, `participant_account_links`, `group_invitations`; exact identity migration is a required subsystem design before coding. Update every financial FK/DTO mapping coherently. Acceptance: pre-signup expense survives verified claim, existing-user acceptance does not duplicate obligations, guessed/reused/revoked tokens fail, two concurrent claims have one winner, forwarded group links do not claim another person's existing debt. Login membership stays distinct from financial participation.

**G02 — Persistent friends and non-group ledger.** XL, Sol H; depends G01/F02/C01/H01. Add explicit friend relationships and friend detail/history; introduce a participant-authorized personal ledger scope and `/api/friends` / `/api/personal-expenses` routes. Reuse split and projection calculations, not group guard bypasses. Serial PRs: zero-balance directory/detail; authorized non-group expense CRUD; debt/payment view. Acceptance: settled friends remain listed, a third party cannot enumerate personal expenses, participant claim preserves history, per-currency friend totals reconcile across groups/personal scope, and errors distinguish an unavailable history from zero balance.

**G03 — Complete group settings and lifecycle.** L, Sol H; depends H01/F02/C01. Finish group-type editing UI using the existing PATCH path. Add archive/unarchive and recoverable creator-only delete/restore, with an explicit unsettled-balance conflict policy; archive stops new entries but preserves reads and export. Replace destructive cascaded UI deletion with soft lifecycle state. Acceptance: archive/unarchive preserves totals, unsettled delete returns actionable conflict, restore is versioned/authorized, old memberships confer no unintended access, creator-only rules hold. Add a retention/purge policy separately before any irreversible purge job exists.

**E03 — Multi-payer contributions.** L, Sol H; depends E01/C01/H01/G01. Add paid contribution rows distinct from owed shares; migrate each old expense to its original single payer. Extend request/response and expense form with paid amounts. Specify deterministic attribution from owed parties to paid contributors and an explicit rounding recipient; do not infer Splitwise's private attribution. Acceptance: sum(paid)=sum(owed)=total; editing both sides is atomic; old single-payer expenses remain identical; net and pairwise results reconcile for self-payment, multiple creditors, tiny amounts, and unequal contributions.

**B01 — Payment detail, correction, date/method/note and refunds.** L, Sol H; depends H01/E02/C01. Extend `Payment`, payment DTOs/controller/service, and a real payment-history screen. Record financial date, method, note, recorder, and audit/version. Add correction/reversal routes with operation identity; retain original records and distinguish a manual record from moving real money. Add a refund/reimbursement entry that reverses the appropriate expense economics with positive validated amounts and a reference, rather than weakening every existing positive-amount constraint. Acceptance: correcting the wrong payer/amount, deleting/restoring a record, and a partial refund reconcile balances/audit once; duplicate retries and stale edits do not double-reverse.

**B02 — Atomic cross-group friend settlement.** L, Sol H; depends B01/G02/F02/C01. Add a preview returning versioned allocations by authorized ledger scope and currency, then a keyed commit that validates and writes all allocations in one PostgreSQL transaction. Lock scopes in UUID order. Acceptance: two groups with opposing obligations settle consistently without a synthetic unexplained friend-only payment; insufficient permission/stale preview/currency mismatch fails the whole request; exact retry returns identical allocations; a raced expense cannot silently change what was settled. Never net currencies together.

**A04 — Account closure with financial preservation.** L, Sol H; depends A03/H01/G01/H05. Add export/closure flow, reauthentication, immediate session revocation, anonymized historical presentation where appropriate, and a documented unsettled-obligation policy. Preserve other participants' accounting and audit access. Acceptance: closed account cannot authenticate/recover to bypass closure, no shared expense/payment cascades away, exported data is authorized, and display/email reuse does not reassign old debts. Retention/deletion requirements must be stated before enabling irreversible personal-data purge.

### M3 — collaboration and usable history

**H02 — Notes, comments, and basic attachments.** L, Sol H; depends H01/G01; private storage configuration required. Add expense notes/discussion and private receipt/photo upload/download/delete through authorized APIs; enforce an allowlist, size limits, generated object names, and lifecycle cleanup. Acceptance: collaborators can discuss, outsiders cannot fetch a guessed URL, deleted/unavailable files show actionable state, malformed files cannot execute as product content, and storage failures never silently drop a saved expense. A receipt photo is not OCR or itemization.

**H03 — Durable activity and notifications.** L, Sol H; depends H01/A02/G01. Replace latest-row inference with paginated committed events; persist notification preferences, read state, and a transactional outbox. Deliver in-app first and email through configured sender; push follows supported platform/channel setup. Acceptance: recorder is distinguishable from payer, edited/deleted events remain visible, retries deliver without duplicate notification records, no notification is sent for a rolled-back write, and preferences/unsubscribe are respected.

**H04 — Recurrence and balance reminders.** L, Sol H; depends E01/E02/C01/H03. Add schedule/rule and occurrence identities, timezone/calendar semantics, cancellation, catch-up limits, and member-change review. Reuse E01's persisted split-input contract; a global saved-default UI is not a prerequisite. Start with daily/weekly/monthly schedules and a documented month-end policy; unique `(schedule, occurrence)` prevents duplicate financial entries. Versioned “change future occurrences” updates the schedule without editing prior generated entries. Balance reminders use notification preferences and bounded deduplication. Acceptance: two workers/restarts produce one occurrence, DST/month-end and missed-run cases are explicit, inactive participants halt for review, and cancellation prevents future entries without deleting history.

**H05 — Bounded history, date filters, and CSV export.** L, Sol H; depends C01/H01; G02 extends scope when integrated. Add cursor APIs with page size default 50/max 100, stable `(financialDate, id)` order, account/group/friend scope, financial-date bounds and updated-after/before filters backed by H01's revision timestamps; map the displayed order explicitly. Stream authorized CSV with currency/code/minor units, readable amount, participant/actor fields, per-person paid/owed/settlement deltas and spreadsheet-injection-safe text. Include transaction IDs so repeated participant rows are not mistaken for separate expense totals. Acceptance: no missing/duplicated rows at equal timestamps, unauthorized groups never export, filtered export totals reconcile by currency, large histories stay bounded, and timezone boundaries include the intended financial dates.

**C02 — Locale and language support.** M, Sol M; depends A01/C01/F03. Extract all UI and accessible strings, support English plus a selected second locale (recommended Hindi for the first acceptance set), use locale-aware dates/numbers/plurals, and persist server-owned locale. Keep currency identity independent. Acceptance: comma/dot/grouped input cannot silently change amount, long translations fit mobile dialogs, screen-reader labels are translated, switching locale leaves stored money unchanged. Add RTL support only with an actual target locale and layout checks.

### M4 — mobility

**H07 — Installable PWA and safe offline workflows.** XL, Sol H; depends E02/A03/H06/H05/C01/H01. Serial slices: installability/app-shell caching; account-scoped read cache with logout purge; IndexedDB durable operation queue for supported expense writes; conflict/reconnect UI. Store the operation identity and expected version with the queued intent. Do not queue real-money transfers or claim all features work offline. Acceptance: flight-mode draft/restart/reconnect writes once, A logout→B login reveals no A cache or pending write, token expiry pauses sync, changed membership/conflicts require review, and pending totals are visually distinct from confirmed server money. Test actual service worker behavior in a production build.

### M5 — Pro workflows after their foundations

**P01 — Expense search.** M, Sol M; depends H05/C01/G02. Add authorized account/group/friend search over description, notes, category, person, date, amount and currency; use PostgreSQL filtering/full-text capabilities first. Acceptance: results and pagination respect ledger permissions, changing membership removes access, currency filters never compare unlike amounts, and search contains no soft-deleted records unless an explicit recovery filter allows them.

**P04 — Spending analytics.** M, Sol M; depends H05/C01/H01. Add date/category/person spending views with separately defined “group total,” “my owed share,” and “cash paid.” Charts have text/table equivalents and per-currency totals. Acceptance: charts reconcile to filtered export after edit/refund/delete, no cross-currency fabricated total, empty/negative-refund periods remain readable, and accessible tables reproduce values.

**P05 — Saved split rules/defaults.** M, Sol M; depends E01/C01/G01. Persist versioned split-rule inputs and validate current membership/currency when applying. Default rules prefill a draft; they never post automatically. Acceptance: saved percentages/weights survive amount changes, removed participants trigger review, legacy exact shares are not misrepresented as a ratio, and changing a default never edits old expenses. Reuse E01's input contract, as recurrence does; core recurrence does not wait for this Pro-listed UX.

**P07 — Full account JSON backup.** M, Sol M; depends H05/H01/G01. Add a versioned export containing authorized account/profile, participants, groups, expenses/contributions/shares/intent, payments, rules, recoverable history, and attachment metadata. Exclude password hashes, token secrets and other users' private account data. Acceptance: a schema validator checks referential completeness and totals per currency on a disposable fixture; CSV and operational database backups remain distinct. Restoring arbitrary imports is a separate feature, not implicitly promised by download.

**P02 — OCR and itemized receipts.** XL, Sol H; depends H02/E03/C01/E02. Choose a provider only after accuracy, cost and retention assessment; add extraction jobs and a reviewable draft with line items, participants, tax/tip/discount allocation, and reconciliation. Acceptance: ambiguous/malformed OCR never posts automatically, item allocations plus adjustments equal receipt total, duplicate scan retries do not duplicate expenses, multi-payer and tiny fractional allocations conserve minor units, and original image access remains private.

**P03 — Explicit FX conversion.** L, Sol H; depends C01/H01/E02. Add reviewed conversion preview/commit retaining original amount/currency, target amount/currency, rate/source/as-of and rounding. Choose provider/cache/staleness policy before external requests. Acceptance: conversion requires confirmation, balances change through an auditable operation once, historical rate stays reproducible after provider updates, stale/unavailable rates are visible, and USD/JPY/KWD rounding matches the preview. Preferences alone never trigger conversion.

### Conditional ecosystem packages

**A05 — Social sign-in and safe account linking.** L, Sol H; depends A02/A03/G01; gate: selected identity provider and registered callback configuration. Use OIDC with state/nonce/PKCE through established Spring capabilities. Never merge accounts using an unverified email. Acceptance: login CSRF/link-hijack/duplicate verified identity races fail, unlink preserves a usable login method, and financial participant IDs do not change.

**B03 — Real-money settlement integration.** XL, Sol H; depends B02/A03/H01; gate: target countries, supported provider and legal/commercial suitability. Separate transfer intent, provider status, webhook verification, idempotency, and ledger allocation; a redirect/click is not a completed payment. Acceptance: signed duplicate/out-of-order webhooks reconcile once, failures do not mark money paid, refunds/disputes remain traceable, and region-unavailable methods are hidden or explained. No blanket Venmo/PayPal requirement.

**H08 — Native iOS/Android delivery.** XL, Sol H; depends stable A03/G02/H05/H07 APIs; gate: supported platforms, budget and distribution choice. Compare a maintained native client with a wrapper using actual offline/push/device requirements before selecting stack. Acceptance includes device UI/accessibility, credential storage, offline conflict flows, notifications and app-store delivery. A PWA does not close this matrix row.

**H09 — Third-party API authorization.** L, Sol H; depends A03/H01/G02/H05; gate: external developer demand and OAuth scope policy. Add revocable scoped grants, consent, rate limits and versioned API docs using established authorization components. Acceptance: one app's revoke/expired scope cannot expose unrelated data, user token and app grant roles stay distinct, and compatibility has contract tests. Do not expose account JWTs as permanent integration keys.

**P06 — Bank/card import.** XL, Sol H; depends A03/E02/C01/H01; gate: countries, aggregator, consent, cost and deletion policy. Import into reviewable drafts with stable provider transaction IDs, pending→posted reconciliation and duplicate detection; user chooses shared allocations. Acceptance: repeated sync, corrections, refunds, reconnect and account deletion do not duplicate expenses or retain access after revoke. CSV import can be an earlier bounded alternative if demand justifies it; it is not bank-sync parity.

**P08 — Subscriptions and entitlements.** XL, Sol H; depends A03/H01; gate: pricing, provider, entitlement definitions and decision to monetize. Add billing/webhook entitlement state only for approved paid features. Acceptance: renew/cancel/refund/retry events converge, lapse never deletes historical money, and current free ad-free/unlimited-entry behavior changes only through explicit product approval. Duo/Trip Pass are packaging decisions, not engineering prerequisites.

**P09 — Early-access/card product.** XL, Sol H; depends P08/B03 only if pursuing that business. Gate: business sponsor, card partner, country and compliance requirements. An early-access list is distinct from a card financial product. No implementation date or generic card infrastructure is reserved now; require a separate design and explicit acceptance contract if approved. Matrix rows remain conditional, never silently marked Done.

## Dependency and ownership rules

- **Ledger owner:** F01 → F02 → C01 → H01 → E03/B01. Although E03 and B01 touch different entities, serialize shared projection/repository changes or establish reviewed ownership before overlap.
- **Account owner:** F03 → A01 → A02 → A03 → A04/A05. Do not concurrently edit `AuthService`, `SecurityConfig`, or profile/session ownership from two branches.
- **Expense editor owner:** E01 → E02 → C01 → E03; P05/recurrence/itemization reuse the resulting contract. One branch owns the editor at a time.
- **Identity migration owner:** G01 must integrate before G02, claim-aware recurrence, multi-payer participant migrations, or native identity clients start.
- **History owner:** H01 precedes restore, durable events, correction, notification outbox, and analytics over deleted data. H05 owns pagination/export DTO changes.
- **Money UI owner:** C01 owns formatter and all denomination wiring; C02 may follow its stable contract, never race its amount parser.
- **Project memory/API docs:** each feature updates its own API contract, but one integration owner resolves shared `Agents.md`/OpenAPI conflicts and confirms final behavior. No stale-doc overwrite.
- Every new batch reads all merged dependency contracts. If a dependency or shared file changes during a branch, rebase and rerun affected verification before PR review.

## Delivery workflow and verification

For each package:

- [ ] Read the design, package contract, source evidence, current `Agents.md`, and merged dependencies. Expand an XL package into reviewable slices and a subsystem plan before implementation.
- [ ] Create an isolated worktree/branch from latest `origin/dev`: `fix/alignment-f01-historical-ledger` for fixes, `feat/alignment-<id>-<slug>` for features, `docs/alignment-f04-contract` for docs. Review actual base before branching; do not reuse the integration branch as a feature base by accident.
- [ ] Add a meaningful failing regression for changed behavior, demonstrate its failure on the base, implement the smallest change, then prove it passes. Documentation-only changes use contract/source verification instead of artificial tests.
- [ ] Run relevant backend/frontend checks; full backend and frontend suites plus formatting/build are required before a code PR. Test migrations and financial concurrency against real PostgreSQL 16; verify browser paths touched by the change.
- [ ] Review access control, integer arithmetic, currency partitioning, retries, histories and race handling. Use a fresh reviewer when implementation is authorized, within the three-stream limit.
- [ ] Commit/push/create a PR to `dev` only under the implementation authorization then in effect. PR states behavior, migration/backfill, evidence and remaining limits. No merge or deployment is implied by creating the PR.
- [ ] After integration, run combined checks and update source memory, own API docs and feature coverage. Start newly ready packages only after shared contracts are integrated.

Canonical commands from repository root (toolchain paths are session-specific):

```bash
cd backend
./mvnw -B spotless:check verify
```

```bash
cd frontend
npm test -- --watch=false
npx prettier --check "src/**/*.{ts,html,scss,json}"
npm run build -- --configuration production
```

For this workspace use `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1` and Maven `-s /workspace/maven-proxy-settings.xml` if those paths still exist. Docker must be available for Testcontainers. Do not substitute H2 for migration/locking evidence. Test counts are recorded from the current run, not copied from the audit's 207/187 baseline.

Release gates: full combined suites, migration rehearsal on a disposable restored dataset, backend/Caddy/PostgreSQL smoke, affected browser flows, deployment tests if delivery files change, clean diff and independent review. Create a `dev → main` release PR when requested; merging main invokes production delivery and requires the then-current authorization. Verify public health/deep links, selected financial flows with owned test accounts, migration outcome, deployment job and release tag before claiming a release. No secrets or other users' data in test artifacts.

## Migration and rollback policy

1. Inventory affected rows and detect broken invariants before mutation. Report old damage separately from new regressions.
2. Rehearse each Flyway change on a disposable PostgreSQL copy, including legacy data and the last released application schema expectations.
3. Add fields/tables first, backfill idempotently with reconciled row counts/totals, then enforce constraints and switch reads/writes. Never edit an already-applied migration.
4. Preserve a schema-compatible application rollback during the compatibility window. After contract-breaking migration, prefer a tested forward fix; restore a backup only as an explicit incident operation with a data-loss assessment.
5. Production promotion retains the existing deployment lock and pre-migration backup safeguards. Test backup restoration separately; file existence alone does not prove recoverability.

## Traceability and decisions

The [81-feature delivery map](2026-10-19-splitwise-alignment-backlog.csv) preserves the audit IDs/statuses, names a primary package and supporting packages, and distinguishes maintain, extend, build, and conditional work. Done rows remain regression constraints, not fake new features. The map includes native, real-money and Pro/business rows so deferred work is visible.

Owner-confirmed decision: all existing amounts are USD (19 October 2026). C01 owns the backfill; no schema or data migration is part of this planning update.

Remaining decisions are needed **at the owning package gate**, not before this plan can be reviewed:

| Decision | Recommended default | Blocks |
| --- | --- | --- |
| Email sender, private attachment storage, push channel | Existing hosting-compatible managed service, minimum required permissions and cost | A02 real delivery, H02 storage, H03 push respectively |
| Second locale | Hindi as first proposed test locale; choose based on intended users | C02 final acceptance dataset |
| Target markets/providers | Pick based on actual audience; no global brand assumption | B03/P06 and SMS invitation delivery |
| Native distribution, public OAuth, social provider | Defer until demand/requirements justify them | H08/H09/A05 |
| Monetization, OCR/FX provider and retention | Keep current free behavior; approve providers when packages start | P08/P09, external P02/P03 calls |

## Plan validation and completion definition

- All 81 audit rows must map to an existing package, with no duplicated row IDs or unassigned features.
- All package dependencies must exist and the dependency graph must be acyclic.
- First batch has three distinct owners with explicit shared-file exclusions. Financial projection starts after lifecycle containment, not concurrently with it.
- Milestone sign-off cites evidence for its acceptance criteria; an endpoint without UI, mocked screen without backend, unverified provider, or PWA without native apps remains partial in the corresponding catalogue.
- Re-run the 14-capability core assessment and all 27 supplied API workflow mappings after M4, and after optional ecosystem work. Report core-web, platform, Pro and conditional outcomes separately. Do not extrapolate completion dates from the rough 46.4% audit indicator.

This is a complete scope/dependency roadmap with an executable first batch. Later subsystem contracts intentionally receive detailed file/test steps at their kickoff, against the then-current schema and APIs, rather than pretending today's source paths will remain unchanged through every migration.
