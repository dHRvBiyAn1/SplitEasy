# Splitwise alignment — parallel batch plan (11 November 2026)

Replaces the batch table in the [remaining delivery plan](2026-11-10-alignment-remaining-delivery.md).
The goal is for up to three agents to work at once without editing the same file. Package
contracts stay in the [roadmap](2026-10-19-splitwise-alignment.md); its global constraints,
TDD/verification workflow and migration policy still apply in full.

## How agents avoid conflicts

1. **One owner per file per wave.** Each wave table lists what every package owns. Another
   package in the same wave may *read* an owned file but must not edit it. If a package needs a
   change in a file it doesn't own, it either wraps the existing code in a new file of its own
   or waits for the next wave. New files are always safe.
2. **Workers never edit the project-memory files.** `Agents.md`, the roadmap, the backlog
   CSV/JSON and this plan belong to the integrator (the parent thread). Each worker puts an
   "Agents.md delta" section (one Progress Log line plus any convention/data-model lines) in its
   own evidence file under `docs/reviews/<date>-alignment/<pkg>.md`. The integrator applies it
   after merging.
3. **Shared registries use anchors.** Before a wave starts, the integrator commits one marker
   comment per package to `openapi.yaml` (under `paths:` and under `components.schemas:`) and to
   `frontend/src/app/app.routes.ts`, for example `# --- g02-2 paths ---`. Each worker adds new
   entries only directly below its own marker. Git merges inserts at separate anchors cleanly.
   Changing an *existing* operation, schema or route requires ownership of that entry, as listed
   in the wave table.
4. **Migration numbers are reserved up front** (table below). Merge in number order. If the
   order has to change, the PR merging out of order renames its unreleased file first. This keeps
   Flyway from refusing to start on dev databases.
5. **Each worker gets its own branch and worktree** from `origin/dev`:
   `feat/alignment-<id>-<slug>`, with a PR to `dev`. The integrator merges and runs the combined
   suites. A worker rebases only when the integrator says a dependency has landed.
6. **Ledger serialization still holds.** The query side of balances, projections and dashboards
   (`BalanceService`, `ObligationProjectionService`, `PairwiseBalanceService`, `DashboardService`)
   has at most one owner per wave.

## Reserved migrations

| Version | Package | Note |
| --- | --- | --- |
| V20 | B01 | payment detail, corrections, refunds (PR #112) |
| V21 | G02.2 | `groups.kind`, `personal_ledgers` |
| V22 | H05 | unused (cursor query runs over a UNION; no index needed) |
| V23 | E03 | `expense_contributions` plus single-payer backfill |
| V24 | G02.3 | unused (personal payments reuse `payments`) |
| V25 | H03 | activity events, outbox, notification preferences |
| V26 | B02 | settlement batches and allocations |
| V27 | A04 | account closure state |
| V28 | H04 | schedules and occurrences |
| V29 | C02 | server-owned locale |
| V30 | H02 | comments and attachment metadata |
| V31+ | M4/M5 | assigned when their wave is planned |

An unused reservation leaves a gap, which Flyway accepts.

## Wave 6 — done (PRs #115, #116, #114 merged to dev 2026-11-11)

| Package | Owns (exclusive this wave) | Must not touch |
| --- | --- | --- |
| **G02.2** personal-expense CRUD ([plan](2026-11-10-alignment-g02-2-personal-ledger.md)) | `Group` entity, `GroupService`, `MembershipGuard`/`GroupWriteGuard` refusal of personal ledgers, `FriendService`/`FriendController`/`dto/friend/*`, new `PersonalLedger*` classes, `DashboardService` (group-list exclusion only), frontend `friends/*`, `modals/new-expense-modal.*` (fixed-pair participants input) | `ExpenseService` (call it, don't change it), `ExpenseRepository`, payment classes |
| **H05** bounded history + CSV | New `HistoryController`/`HistoryService`/`dto/history/*` and a new JDBC query class (no edits to `ExpenseRepository`/`PaymentRepository`), frontend `groups/group-detail.*` history list, new `history/*` | `DashboardService`, expense editor, friends UI |
| **E03-A** multi-payer: subsystem plan + backend | Subsystem plan doc (XL, written before coding), `ExpenseService`, `ExpenseRepository`, `Expense*` entities, `dto/expense/*`, `BalanceService`, `ObligationProjectionService`, `PairwiseBalanceService`, split strategies | All frontend files; `DashboardService` |

Interactions:
- G02.2 reuses `CreateExpenseRequest`/`ExpenseResponse` unchanged; E03-A adds optional
  contribution fields. Merge G02.2 first, then E03-A, so personal expenses inherit single-payer
  backfill.
- H05's CSV "paid" column reads `expenses.paid_by` until E03-A lands. Merge H05 before E03-A;
  E03-A switches the export to contributions as part of its own PR.

## Wave 7 — done (PRs #118, #119, #120 merged to dev 2026-11-12)

Design and final ownership: [Wave 7 package plans](2026-11-12-alignment-wave7.md) (H03 leaves the dashboard feed alone this wave).

| Package | Owns | Must not touch |
| --- | --- | --- |
| **E03-B** multi-payer editor | `modals/new-expense-modal.*`, `expenses/*` models/service, expense detail rendering in `groups/group-detail.*` | Friends UI, backend ledger |
| **G02.3** friend debt/payment view | `FriendService`, `dto/friend/*`, new personal-payment wrapper over `PaymentService` (call, don't change), frontend `friends/*`, `modals/settle-up-modal.*` | `DashboardService`, expense editor |
| **H03** durable activity + notifications | `ExpenseHistory`, `LedgerHistory` (emit outbox events where revisions are already written), `GroupService` lifecycle/member events, `DashboardService` activity feed, new `activity/*` and `notification/*` (backend + frontend), notification section in `profile/*` | Expense editor, friends UI |

H03 hooks into the revision writers that every financial write already calls, so it never edits
the expense or payment services.

## Wave 8

| Package | Owns | Must not touch |
| --- | --- | --- |
| **B02** cross-group settlement | New `settlement/*` (backend preview/commit, UUID-ordered locks), `PaymentRepository` (insert path), frontend settle entry in `friends/*` and `dashboard/*` | `AuthService`, profile |
| **A04** account closure | `AuthService`, `AuthSessionService`, `AccountLifecycleService`, `SecurityConfig`, `User` entity, `ProfileService`, frontend `profile/*`, `auth/*` | Settlement, recurrence |
| **H04** recurrence + reminders | New `recurrence/*` (scheduler, occurrence identity, calls `ExpenseService`), reminder sender via H03 outbox, frontend new `recurring/*` plus a repeat control in `modals/new-expense-modal.*` | Profile, settlement |

## Wave 9 — locale wave

C02 rewrites strings in almost every template, so this wave's other slots are backend-only.

| Package | Owns | Must not touch |
| --- | --- | --- |
| **C02** locale | Every frontend `*.html`, i18n config, locale-aware formatting helpers, locale field in `ProfileService`/`User` | Backend outside profile locale |
| **H02-A** comments + attachments backend | New `comment/*` and `attachment/*` backend packages, storage adapter (needs storage decision first) | All frontend |
| **P07-A + P01-A** backup and search backend | New `export/*` and `search/*` backend packages reading H05's query class | All frontend |

Then **R2**: the release gate for M2 + M3 (same checklist as R1, migrations V20 to V30).

## Wave 10 — after R2

| Package | Owns |
| --- | --- |
| **H07** PWA/offline (XL, subsystem plan first) | Service worker, `core/*` HTTP/offline queue, app shell |
| **H02-B + P01-B + P07-B** feature UIs | New `comments`, `search` and `backup` frontend folders; attachment panel in expense detail |
| **P04 + P05** analytics, saved split rules | New `analytics/*`; split-rule store plus prefill hook in the expense editor |

P02 (OCR) and P03 (FX) each wait for a provider decision and then run alone. The seven
conditional packages stay unscheduled.

## Starting a worker

Prompt the worker with: package ID, this plan's wave row (owned files + forbidden files), the
reserved migration number, its roadmap contract, the branch name, and "PR to `dev`, no merge, no
`Agents.md` edits." The integrator runs at most three workers at once.
