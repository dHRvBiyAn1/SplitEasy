# Splitwise alignment: proposed design and delivery scope

Date: 19 October 2026. Status: **first batch merged; F02/A01/E01 implementation authorized and in progress**. Baseline: released `v0.1.0` (`aa287fb`), identical source tree to inspected `d0ea1b7`. Read with the [implementation roadmap](../plans/2026-10-19-splitwise-alignment.md) and [first-batch plan](../plans/2026-10-19-splitwise-alignment-foundation.md).

Workflow: [Matt Pocock's skills](../../agents/engineering-workflow.md), selected by the user to replace Superpowers. The existing document directory is retained for stable links.

## Goal and scope

Make SplitEasy a trustworthy shared-expense product, then close the audited Splitwise core gaps, then add useful Pro capabilities. Feature parity means equivalent user outcomes, not copying Splitwise's endpoint names, private algorithms, commercial packaging, or visual design.

The evidence baseline is `/workspace/issue-context/SPLITWISE-ALIGNMENT.md`, its 81-row feature matrix, the supplied 27-operation OpenAPI analysis, and the REA evidence index. The three reproduced financial scenarios are in `alignment-financial-runtime.json`. REA's public JavaScript analysis did not execute a logged-in Splitwise session; it does not prove private implementation details.

Planning assumptions: retain the responsive Angular web product and current deployment; finish core workflows before OCR, conversion, bank import, subscriptions, or native applications. Keep existing free unlimited entry and ad-free behavior. Conditional ecosystem work remains visible in the backlog without being treated as an approved integration purchase or provider choice.

## Global constraints

- Java 21, Spring Boot 4.1, PostgreSQL 16/Flyway, Angular 21 standalone components/signals, Vitest; CI Node 22.
- Maximum three concurrent implementation workstreams. No Astra; Sol reasoning must not exceed high. Models and branches are specified per work package.
- Branch from the latest checked `origin/dev`; implementation PRs target `dev`. Integrate and verify shared changes before starting dependent branches. Release promotion is a separate `dev` to `main` PR.
- The user authorized implementation on 19 October 2026, including separate branches and PRs to dev. F01/F03/F04 merged in PRs #95–#97 with verified dev CI. The user explicitly requested F02/A01/E01 next; these branches share verified dev `337858d8197d9228d80a03dfcc7d3aef654b4e04` and end at reviewed PRs to dev. Merge, production deployment, new remote issues and paid/provider setup remain separate actions requiring applicable authorization.
- Keep UUID identities, DTO boundaries, `MembershipGuard`, `Emails.normalize`, and 403-before-404 for unauthorized group access. Financial participation never grants group access.
- Keep derived balances, integer minor-unit arithmetic, the current 1_000_000_000_000 per-entry bound, overflow-safe validation, and consistent financial read snapshots. Never add floats or stored mutable balance totals.
- Keep current flat group editing permissions and creator-only group deletion until a documented lifecycle change; do not add roles solely for parity.
- Keep payment operation identity and exact replay semantics; no automatic retry of a changed write, conflict, or real-money transfer.
- Use additive, forward-compatible migrations. Never reinterpret legacy amounts as a different currency or delete historical payments to force balance agreement.
- Use existing components, services, libraries, and platform capabilities before adding dependencies. No generalized ledger engine, event-sourcing rewrite, microservices, or speculative abstraction.

## 1. Financial correctness and historical participants

Separate **active membership**, which grants access and eligibility for new expenses, from **financial participation**, which is established by persisted expense contributions, shares, and payments. The first fix can derive financial participants from existing references plus the active roster; it needs no membership soft-delete migration. Batch-load the union of referenced user IDs, including users whose membership was removed by the old application. A user missing from the current roster must never disappear from the financial result.

Net balances must sum to zero for every group and, after currency work, every group/currency. Every user's pairwise balances must sum to that user's net. Include zero-balance active members; include historical financial participants where needed to explain or settle the ledger. Never invent join/leave times, delete payments, or silently alter old shares during repair.

**Initial containment policy:** leaving/removing requires both zero net and no nonzero bilateral obligations. This deliberately blocks the reproduced net-zero intermediary case. Editing or deleting an expense that references an inactive participant returns 409 with instructions to re-add the participant before changing the history. The existing add-member workflow restores eligibility without changing that person's historical user identity. Former members retain no group access. Existing damaged histories become visible; their repair is an explicit re-add-and-correct operation, never an automatic migration of debt.

The containment policy is intentionally stricter than the final simplified-group experience. The next ledger package replaces bilateral checks with the reviewed canonical obligation projection for the selected mode. Historical correction packages can later permit more changes by validating their consequences, retaining an audit trail, and notifying affected participants. Do not claim the initial fix completes all lifecycle parity.

Serialize each group's financial mutations and membership changes on that group's database row. Authenticate before loading the row and recheck membership after acquiring the lock. Write transactions use READ COMMITTED so checks after waiting observe committed membership and ledger changes; existing read-only aggregate transactions remain REPEATABLE READ. This also preserves keyed payment `ON CONFLICT` replay behavior. Recheck conflict/deadlock handling with deterministic PostgreSQL races. Do not use Java process locks or sleeps as synchronization.

## 2. One obligation interpretation for every screen

Introduce one internal obligation projection over a complete financial snapshot:

- Direct mode nets recorded obligations within each pair. The UI explicitly calls these direct obligations, including equal amounts owed and owing across different people.
- Simplified mode uses deterministic net-debtor/net-creditor matching; dashboard, People, group balances, settlement actions, and leave eligibility consume that same projected result. UUID tie-breaking stabilizes equal-amount outcomes.
- Raw expenses and payments remain the history in both modes. Changing the setting changes the displayed obligation interpretation; it does not rewrite transactions.
- A net-derived settlement preview in direct mode must clearly say that it can leave offsetting direct obligations. It must not produce a generic “all settled” success while direct obligations remain.
- Switching modes cannot create an actionable debt involving a former member. Reject such a change with 409 and explicit repair guidance; do not silently hide the person.

For the audit's A→B→C 1,000-unit chain, simplified mode projects A→C 1,000. Recording that transfer makes every simplified surface zero. Direct mode may retain a cycle; it must display and explain it consistently. The greedy method conserves money and returns at most N−1 transfers; it is not promised to minimize transactions globally.

## 3. Account truth, ownership, and sessions

First remove the appearance of account mutation from local-only profile controls. Then implement authenticated `GET /api/me` and `PATCH /api/me` for server-owned name/preferences. Expose existing `User.createdAt`; do not add a second registration date column. Token name/email claims are display hints, not authoritative fresh account records.

Use verified, expiring, single-use token workflows for email verification, password reset, and email changes. Do not change the login email until ownership of the replacement address is verified. Normalize addresses and arbitrate uniqueness in PostgreSQL. Phone is an unverified contact preference until an explicitly chosen verification channel exists; never use it to claim someone else's financial identity.

Implement server-side revocable sessions before claiming all-device logout or silently extending sign-in. Retain one-hour access tokens during the initial migration; rotate refresh credentials if introduced, detect reuse, revoke after sensitive account changes, and test trusted origin/CORS/CSRF behavior. Keep auth limits; multi-instance operation requires shared enforcement before scaling.

Account closure revokes access and removes personal presentation where appropriate without deleting other people's financial history. Export and unsettled-balance policy are explicit parts of that package.

## 4. Currency before conversion

Persist ISO currency identity with every expense and payment. Move to explicitly named integer `amountMinor` and `shareMinor` contracts when supporting different exponents. Validate currency code and exponent using a supported catalogue; test USD/EUR/INR (2), JPY (0), and KWD (3). Formatting uses `Intl.NumberFormat`; parsing uses checked decimal-to-integer conversion, never floating-point ledger calculations.

Every aggregate, debt projection, payment, leave guard, friend settlement, report, and export partitions by currency. An account preference selects the default for new entries and presentation locale; it never converts existing transactions. Never add unlike currencies into a single monetary total.

**Owner-confirmed legacy denomination (19 October 2026):** the user confirmed that all existing amounts are USD. C01 may use USD for the legacy backfill and non-null currency migration. This decision does not implement a migration or add currency to the current schema; C01 owns those changes. Keep the existing `amountCents` wire model until C01 defines and rolls out the explicit minor-unit contract. Never use an individual user's preferred currency to relabel shared historical expenses.

FX conversion is a later feature with rate source, timestamp, original amount, rounding, and consent recorded; it is distinct from tracking several currencies.

## 5. Split intent, contributions, and retries

Retain both the final owed shares and the original split inputs: equal participant set, exact amounts, percentage basis points, share weights, or adjustments. Original intent is absent from old non-equal expenses; present those as exact amounts and never reverse-engineer percentages. Preserve the current payer-remainder policy until multi-payer support explicitly selects a deterministic rounding recipient.

Model paid contributions separately from owed shares for multi-payer expenses. Enforce sum(paid)=sum(owed)=total for each expense/currency. Define deterministic pairwise attribution; net balances alone do not determine who owes which payer. Saved rules and recurrence consume the persisted split intent only after this contract exists.

Extend operation identities to expense/group creation and later composite writes. Scope keys to actor/resource; exact retries return the original result, changed requests conflict. Durable queues retain the same identity. Optimistic versions on editable financial resources detect stale GET→PUT overwrites; introducing this is a new contract, not a claim that the existing generic 409 handler already protects stale forms. Preserve disjoint group PATCH behavior.

## 6. Social identity and non-group expenses

Build a financial participant identity distinct from a login account when introducing unregistered participants. Existing user IDs retain stable mappings. Invitations have expiry, hashed tokens, revocation, deduplicated delivery, and explicit acceptance. Claiming a participant requires verified ownership and an auditable atomic mapping; display names and unverified phone numbers are insufficient proof.

Friends remain visible at zero balance. A friend detail view shows authorized history by currency. Non-group expenses use an explicit participant-authorized ledger scope, not a magic public group ID and not a bypass of group authorization. Reuse arithmetic and projection logic while keeping scope checks separate.

Cross-group settlement is a transaction allocating a payment across authorized group/currency obligations with one operation identity. Do not persist a freestanding friend payment that fails to clear the underlying group debts.

## 7. Recoverability and collaboration

Introduce transactional audit records for expense/payment changes with actor, time, resource version, and before/after data needed for recovery. Soft deletion, restore, archive, and group recovery have defined access and unsettled-debt rules. Do not call today's latest-15-row dashboard a change log.

Comments and basic receipt uploads are core features. Private attachments require authorization on download, type/size checks, storage lifecycle, and removal handling. OCR and itemization come later and always require user confirmation before financial posting.

A database outbox connects committed events to notifications and reminders; retries must not duplicate financial writes. Recurrence uses stable schedule/occurrence identities, original split intent, timezone/calendar rules, explicit cancellation, and catch-up policy. Start with in-app/email channels once delivery infrastructure is selected; push depends on platform permission and provider setup.

## 8. History, mobility, and optional ecosystem work

Add bounded cursor history, date filters, and CSV export before large-scale search/analytics. Define UTC storage, user-local date presentation, stable sort tie-breaking, and safe spreadsheet export. Account JSON backup is a separate complete-data export capability.

Use navigation/focus revalidation first for cross-device freshness. Introduce an account-scoped offline cache and durable operation queue only after idempotency, versions, and logout cleanup are reliable. Explicitly show pending versus confirmed money. Installability and queued-write support are separate milestones.

Localization requires all UI strings, dates, decimal entry, plurals, and assistive text; test at least one second locale. Native iOS/Android, real-money providers, social sign-in, public OAuth, bank import, subscription packaging, and card products require their own platform/business decisions. Keep these as conditional packages rather than claiming web delivery completes them.

## Completion criteria

Each package must have observable acceptance evidence, current API documentation, and regression coverage for its financial/access risks. The final core review reruns the original runtime scenarios, reviews all 81 feature rows, and rechecks all 27 supplied API workflows. A feature is Done only when the end-to-end workflow works; backend-only endpoints and mock-only UI evidence remain partial. Report web core, native/platform, Pro, and commercial choices separately instead of asserting a universal parity percentage.

No calendar dates or cost estimates are asserted. Size labels in the roadmap indicate relative scope and uncertainty, not promised duration.
