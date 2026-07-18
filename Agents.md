# AGENTS.md — SplitEasy (Splitwise clone)

Project living memory. **Read before a task; update after one** — especially
Conventions, Data Model, and the Progress Log (append one line per shipped feature).

## Overview

Expense-splitting web app: users create groups, add expenses split among members
(equal / exact / percentage), and the app tracks who owes whom, plus settle-up
payments and a debt-simplification suggestion (minimize transactions). Balances are
**derived on read**, never stored.

## Stack

- **Backend**: Java 21, Spring Boot 4.1, Maven — layered `controller → service → repository → entity`.
- **DB**: PostgreSQL 16, Spring Data JPA + **Flyway** migrations (`ddl-auto=validate`).
- **Frontend**: Angular 21 standalone components + signals; **Vitest** tests. Angular
  Material present but only its `--mat-sys-*` colors are remapped to tokens — screens use
  plain semantic HTML.
- **Auth**: Spring Security + JWT (HS256, symmetric) via `oauth2-resource-server`. Access
  tokens only (1h), no refresh yet.

## Run & Test

Prereqs: Java 21, Node 22+, Docker Desktop running (dev DB **and** backend tests via Testcontainers).

| Step | Command | Port |
|---|---|---|
| DB | `docker compose up -d` (creds `spliteasy`/`spliteasy`/`spliteasy`) | **5433** (5432 taken by system Postgres) |
| Backend | `cd backend && ./mvnw spring-boot:run` (Flyway runs on startup) | **8080** |
| Frontend | `cd frontend && npm start` (first: `npm install`) — proxies `/api/*` → 8080 | **4200** |

- **Health**: `curl localhost:8080/api/health` → `{"status":"UP","service":"spliteasy"}` (built in `HealthController`, no service).
- **Tests**: backend `./mvnw test` (throwaway Postgres via Testcontainers); frontend `ng test --watch=false` (Vitest headless — use `vi.spyOn().mockReturnValue()`).
- **JWT config**: signs with `app.jwt.secret` (**no default** — dev secret in `application-dev.properties`; non-dev MUST set `APP_JWT_SECRET` ≥32 chars or boot fails). `iss`=`app.jwt.issuer` (default `spliteasy`) is validated on decode. Lifetime `app.jwt.expiration-seconds` (3600).
- **CORS**: `app.cors.allowed-origins` (dev `http://localhost:4200`), wired via a `CorsConfigurationSource` bean into the Security chain (`http.cors()`), not MVC mappings.
- **Gotcha**: esbuild `spawn ... -88` / exit 137 = corrupt binary sig → `rm -rf node_modules/@esbuild node_modules/esbuild && npm install`.

## API

All under `/api`; all except `auth/*` and `health` require `Authorization: Bearer <jwt>`.
Group-scoped routes are member-only (**403 before 404** — see Conventions).

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | issue access token |
| GET | `/health` | liveness |
| GET | `/dashboard` | one aggregated payload for the shell + landing page |
| GET/POST | `/groups` | list mine / create |
| GET | `/groups/{g}` | detail (members) |
| POST | `/groups/{g}/members` | add by email |
| GET/POST | `/groups/{g}/expenses` | list summaries / create |
| GET/PUT/DELETE | `/groups/{g}/expenses/{e}` | detail / full-replace edit / hard delete (204) |
| GET | `/groups/{g}/balances`, `/groups/{g}/balances/mine` | net-per-member / pairwise to me |
| GET/POST | `/groups/{g}/payments` | settle-up history / record |
| GET | `/groups/{g}/debt-simplification` | minimal transfers |

## Architecture

- DTOs at the controller boundary — **entities are never serialized**. DTOs are Java
  records grouped in sub-packages under `com.spliteasy.dto`: `auth/ group/ expense/
  payment/ balance/ dashboard/ common/`.
- Frontend: one Angular service per backend resource; all HTTP via `core/api/api.service.ts`
  (base `/api`); `authInterceptor` attaches the token + handles 401; `authGuard` on private routes.
- **UI tokens live in one place**: `frontend/src/app/styles/_tokens.scss` (colors, type,
  spacing, motion as CSS vars) + `_ui.scss` (`.dc-*` primitives). Both `@use`d once in
  `styles.scss`. Reference `var(--…)` / `.dc-*` — never hardcode. Design ref `design/evenly.dc.html`.

## Backend Conventions

- **Spring Boot 4.1 gotchas**: modular starters (`spring-boot-starter-webmvc`, per-starter
  `-test`); `@WebMvcTest` in `org.springframework.boot.webmvc.test.autoconfigure`;
  Testcontainers 2.x artifacts `testcontainers-postgresql`/`-junit-jupiter`. **Jackson 3**:
  classes under `tools.jackson.*` (annotations still `com.fasterxml.jackson.annotation`).
- **Lombok is used** (PR #22): entities `@Getter @Setter @NoArgsConstructor(access =
  AccessLevel.PROTECTED)` + business constructors; controllers/services `@RequiredArgsConstructor`
  (exception: `HealthController` keeps its `@Value` ctor). DTOs stay records.
- **UUID PKs** everywhere (`@UuidGenerator`, app-side so it works under `validate`; Postgres `uuid`).
- **Auth**: tokens issued in `JwtService` (claims `sub`=user UUID, `email`, `displayName`),
  validated by the resource-server filter (`NimbusJwtDecoder` in `SecurityConfig`). Read caller as
  `@CurrentUserId UUID` (resolver in `config/`) — not raw `Jwt`. Passwords BCrypt.
- **Reuse these, don't re-inline**: `MembershipGuard.requireMember(groupId,userId)` (the group-membership
  check → 403); `@CurrentUserId`; `Emails.normalize()` (trim+lowercase, canonical form for lookup/storage).
- **403 before 404, uniformly**: every group-scoped op calls `requireMember` *before* loading by id,
  so a non-member gets 403 even for a nonexistent group (API never reveals existence). Followed by
  Group/Expense/Balance/Payment services.
- **Group authz is flat**: any member can view, add members, and **edit/delete any expense** (matches
  Splitwise; roles later via `GroupMembership`).
- **Money = integer cents end-to-end** (`long`/`BIGINT`), never float. `amountCents` bounded
  `@Positive @Max(1_000_000_000_000L)` (≤$10B) on create-expense/record-payment → 400 over cap.
- **Splits use the Strategy pattern** (`service.split`): `Equal`/`Unequal`/`PercentageSplitStrategy`
  `@Component`s selected via `Map<SplitType, SplitStrategy>` in `ExpenseService` — **no switch on type**.
  New types implement the interface. Build inputs via `SplitContext.forEqual(...)` / `.forSplits(...)`
  factories (never the raw ctor). `100%` = `TOTAL_BASIS_POINTS = 10_000`. Rounding
  (`SplitStrategy.absorbRemainder`): floor each share, **payer absorbs leftover cents** (else first
  id-sorted participant); shares always sum to the total.
- **Expense edit = full-replace PUT** (reuses `CreateExpenseRequest`): shares recomputed via the same
  strategy path; participants cleared and **flushed before re-insert** (unique `(expense_id,user_id)`).
  **Delete = hard delete** (204; FK-cascade removes participants). Balances re-aggregate, so edits/deletes
  reflect automatically — no special-casing.
- **Indexes** live in Flyway migrations, named `idx_<table>_<cols>` (unique constraints
  `uq_<table>_<cols>`). Every FK column is indexed; **filter+sort paths get a composite
  `(filter_col, sort_col DESC…)`** whose leftmost prefix also serves the plain filter, so
  no redundant single-column index is kept alongside it (see V7 for expenses/payments).
- **Avoid N+1**: `join fetch m.user`; member counts via one aggregate query; expense list via one query
  (payer joined + count as correlated subquery, `ExpenseRepository.findSummariesByGroupId`); detail via `join fetch`.

## Frontend Conventions

- Angular 21 standalone + signals; **no `@angular/animations`** (Material uses CSS animations — don't add
  `provideAnimations*`). Motion, where added, is CSS + tokens.
- Modal `<form>`s use `(submit)="$event.preventDefault(); save()"` — **not** `(ngSubmit)` — because modals
  use bare `[formControl]`s (no `formGroup`/`NgForm`), so a submit would otherwise reload the page.
- **`.dc-amount` is the single owed/owing color convention** (`--owed` green / `--owes` pink / `--settled`
  muted; small text uses darkened `--pos-strong`/`--neg-strong` for ≥4.5:1). A **net balance** uses the
  modifiers; a **plain total** (expense amount, payment) uses `.dc-amount` bare. Reuse — don't re-derive.

## Design System ("Evenly") — complete

Editorial system on **every** screen (imported from Claude Design, `design/evenly.dc.html`): **Instrument
Serif** display + **Hanken Grotesk** UI; lime accent `--accent` `#B6F531` on near-black `--on-accent`;
green-tinted near-white `--bg`; **sharp corners** (`border-radius: 0`); 16px cards; circular avatars. All
values in `_tokens.scss`. **Standing rule: new screens reuse the tokens + `.dc-*` + `.dc-amount` — never
reintroduce Material components or hardcode colors/spacing/owed-owing logic.** Auth screens (`/login`,
`/register`) are full-bleed (no shell).

- **Shell + dashboard**: sidebar shell (`App`) + landing page from one `GET /api/dashboard` cached in
  `DashboardService` (a signal shared by sidebar + page — call `dashboard.refresh()` after any mutation).
- **Theme**: OS default, **user-overridable**. `_tokens.scss` applies `light-tokens`/`dark-tokens` mixins to
  `:root`, the `prefers-color-scheme: dark` query, and `:root[data-theme='light'|'dark']` (the attribute
  out-specifies the query so user choice wins). `ThemeService` (`core/theme.service.ts`) stamps `data-theme`
  on `<html>` and persists `system`/`light`/`dark` to localStorage `spliteasy.theme`; driven by the profile Appearance toggle.
- **Global modals** (`app/modals/`): `ModalService` signal picks which of New group / New expense / Settle up
  is open; `ModalsComponent` (mounted once) renders it over a shared `ModalShellComponent` (backdrop + Esc).
- **Group detail**: header + pairwise balance pills (`/balances/mine`) + month-grouped expense list (category
  icon, date chip, per-viewer `you lent`/`you borrowed`, expandable rows with split + Edit/Delete).

## Data Model

UUID PKs throughout. Migrations in `backend/src/main/resources/db/migration`.

- **users** (V2) — `id`, `email` (unique), `password_hash`, `display_name`, `created_at`.
- **groups** (V2) — `id`, `name`, `created_by`→users, `created_at`; +`type` (V6: `HOME/TRIP/DINING/EVENT/OTHER`, default OTHER).
- **group_memberships** (V2) — `id`, `group_id`→groups (CASCADE), `user_id`→users, `joined_at`. Unique `(group_id,user_id)`; indexed both.
- **expenses** (V3) — `id`, `group_id`→groups (CASCADE), `description`, `amount_cents` BIGINT (>0), `paid_by`→users,
  `split_type` (V4: `EQUAL/UNEQUAL/PERCENTAGE`, default EQUAL), `created_at`; +`category` (V6, 8-value enum) +`spent_on` DATE (V6, user-chosen date ≠ created_at). Composite index `(group_id, spent_on DESC, created_at DESC)` (V7, serves the ordered list + feed); `paid_by` indexed.
- **expense_participants** (V3) — `id`, `expense_id`→expenses (CASCADE), `user_id`→users, `share_cents` BIGINT (≥0). Unique `(expense_id,user_id)`.
- **payments** (V5) — `id`, `group_id`→groups (CASCADE), `payer_id`/`payee_id`→users, `amount_cents` (>0), `created_at`. `CHECK(payer≠payee)`. Composite index `(group_id, created_at DESC)` (V7, serves the ordered history + feed); `payer_id`/`payee_id` indexed. A settle-up is a **direct transfer**, a separate entity from Expense.

User↔Group is many-to-many via memberships; creating a group auto-inserts the creator's membership. All three split types store into the **same `share_cents`** column (no per-type storage), and each expense's shares sum exactly to its amount.

## Balances & Splits (the math)

- **Balances are derived, not stored**: a member's net = `paid − owed + paymentsMade − paymentsReceived`,
  computed via a fixed number of aggregate `GROUP BY` queries (never a per-expense loop). Because shares sum to
  amounts, **group balances always sum to zero** (test invariant) — holds with expenses + payments mixed.
  **Overpayment allowed** (net-per-person model; flips sign past zero).
- **Three views, one source of truth** (persisted expense/payment rows): (1) `BalanceService` net-per-member;
  (2) `DebtSimplificationService` minimal transfers; (3) `PairwiseBalanceService` who-owes-whom-to-me per
  counterparty (drives dashboard cards, People list, settle-up, balance pills). Pairwise is **not netted across
  people** (can show "Dan owes you $50" and "you owe Sofia $50" at once); pairwise nets sum to the member's net.
- **`GET /api/dashboard`** assembles one payload: cross-group totals (`totalNetCents = owed − owe`), per-group
  cards, aggregated people, per-group `Settlement[]`, and a merged recent-activity feed (expenses + payments,
  newest first, capped 15) with per-item `viewerDeltaCents` (+lent / −borrowed; 0 for settlements).
- **Debt simplification** is read-only over balances (reuses `BalanceService.computeBalances`, inherits 403):
  greedy max-creditor/max-debtor match, ≤N−1 transfers, excludes zero balances, no leftover cents. Acting on a
  suggestion records a normal `Payment`. Frontend seeds the settle-up form via the **`SettlePrefill`** union
  (`{kind:'balance'}` vs `{kind:'transaction', payerUserId, payeeUserId, amountCents}`).
- **Split-type semantics** (default EQUAL): UNEQUAL carries `splits` in **cents** (must sum to `amountCents`);
  PERCENTAGE carries `splits` in **basis points** (must sum to 10000). Bad sums → 400 with a clear message,
  never 500. Frontend converts via `dollarsToCents`/`percentToBasisPoints` and gates submit on a live total.

## Domain Rules

- Monetary amounts are integer cents — never float.
- Every split sums exactly to the expense total; rounding remainder handled deterministically (payer absorbs).

## Known Gaps / TODOs

- **Refresh tokens** — not implemented; 1h access tokens only, re-login on expiry. Add before real deploy.
- **Register reveals email existence** (409 on duplicate) — fine for now; revisit if enumeration matters.
- **Profile prefs are client-side only** (PR #23) — phone/currency/avatar-color/theme in localStorage,
  "Member since" = first-visit month. No profile-update endpoint or signup-date column yet.

## Git Workflow

- One branch per feature: `feat/<name>` (or `fix/`, `refactor/`, `docs/`). Conventional commit messages.
- Commit/push only when asked; if on `main`, branch first. Before a PR: run full backend + frontend suites,
  no lint errors, include a "Testing" section with end-to-end evidence.

## Progress Log

*(One line per shipped feature, oldest → newest.)*

- [x] Project skeleton — `feat/project-skeleton`: Boot 4.1/Java 21 layered backend + Postgres/Flyway (5433), Angular 21 frontend, `/api/health` verified end-to-end. B2/F4.
- [x] User & group management — `feat/user-group-management`: User/Group/Membership (V2), Security+JWT, register/login, group create/add-member/list/detail (403 non-members). B19/F16.
- [x] Expense equal split — `feat/expense-equal-split`: Expense/Participant (V3), payer-absorbs-remainder calc, create/list/get expenses (member-only), Angular expense panel. Single-query list (no N+1). B41/F21.
- [x] Group balances — `feat/group-balances`: `BalanceService` net-per-member via 2 aggregate queries, `/balances` (member-only), auto-refreshing panel. Zero-sum asserted. B48/F26.
- [x] Unequal & percentage splits — `feat/exact-percentage-splits`: `split_type` (V4), `splits` list (cents/basis points), 400 on bad sums, toggle UI with live total. B59/F27.
- [x] Split-strategy refactor — `refactor/split-strategy-interface`: replaced static calc + switch with `SplitStrategy` beans + `Map` lookup; behavior unchanged (integration tests unmodified). B62.
- [x] Edit & delete expenses — `feat/edit-delete-expense`: full-replace PUT (recompute + flush before re-insert) and hard-delete DELETE (204), member-only; form reused for edit. B69/F29.
- [x] Settle up — `feat/settle-up`: `Payment` (V5) + payments endpoints; nets into balances via same aggregate path; overpayment flips sign; prefill→confirm→history UI. B78/F31.
- [x] Debt simplification — `feat/debt-simplification`: read-only `/debt-simplification`, greedy max-creditor/debtor (≤N−1, no leftover cents); "Simplify debts" panel with `SettlePrefill`. B93/F33.
- [x] Dashboard redesign + global modals — PR #18 (`dc76572`): sidebar shell + `/api/dashboard` landing (`DashboardService` signal), global modal system (`app/modals/`), redesigned group detail; old inline `*-panel`s removed.
- [x] UI polish pass — PR #21 (`b8bea88`): sharp modals, ~10% tighter spacing, settle-up confirm card, whole-card click targets + restored buttons, expandable expense rows, motion/hover. Verified in browser.
- [x] Backend Lombok + DTO sub-packaging — PR #22 (`refactor/backend-lombok-dtos`): entities→Lombok, controllers/services→`@RequiredArgsConstructor`, flat `dto/`→resource sub-packages. Behavior unchanged. B97.
- [x] Query performance indexes — migration V7 (`V7__query_performance_indexes.sql`): composite
  `idx_expenses_group_spent_on (group_id, spent_on DESC, created_at DESC)` and
  `idx_payments_group_created_at (group_id, created_at DESC)` for the group list + dashboard
  feed + settle-up history; drops the now-redundant single-column `idx_expenses_group`/`idx_payments_group`. Validated against the live schema.
- [x] Profile page + theme toggle — PR #23 (`feat/profile-page`): `/profile` route, sidebar "View profile" link, view/inline-edit (name/email/phone/currency/avatar-color), System/Light/Dark toggle via `ThemeService`. Prefs client-side only (see Gaps). Verified in browser.
