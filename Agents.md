# AGENTS.md — SplitEasy (Splitwise Clone)

This file is the project's living memory. Agents should read it before starting
any task, and update it after completing one — especially the "How to run",
"Conventions", and "Progress Log" sections, which will be sparse at first and
fill in as we build.

## Project Overview

A Splitwise-style expense-splitting web app. Users create groups, add expenses,
split them among group members (equal, exact, or percentage splits), and the
app tracks who owes whom. Includes a debt-simplification feature (minimize the
number of transactions needed to settle up).

## Stack

- **Backend**: Java 21, Spring Boot 4.x, Maven
- **Database**: PostgreSQL (via Spring Data JPA + Flyway for migrations)
- **Frontend**: Angular 21, standalone components, Angular Material
- **Auth**: Spring Security + JWT (HS256, symmetric secret) via
  `spring-boot-starter-oauth2-resource-server`. Access tokens only — no refresh
  tokens yet (see TODO under Domain Rules).
- **API style**: REST, JSON

## Architecture

- Backend: layered — `controller` → `service` → `repository` → `entity`
- DTOs at the controller boundary; never expose JPA entities directly in API responses
- Frontend: feature-based module structure, one Angular service per backend resource
- Shared HTTP interceptor for auth token + error handling
- **UI design tokens live in one place**: `frontend/src/app/styles/_tokens.scss`
  (colors, typography, spacing as CSS custom properties) + `_ui.scss` (shared
  `.dc-*` primitives: buttons, fields, cards, loading bar, `.dc-segment`
  segmented control, `.dc-avatar`). Both are `@use`d once in
  `frontend/src/styles.scss`. Components reference `var(--…)` / the `.dc-*`
  classes — never hardcode hex values. Design reference: `design/evenly.dc.html`.
- **Money amounts use one shared convention** — `.dc-amount` (+ `--owed` green /
  `--owes` pink / `--settled` muted) in `_ui.scss` is the single source of the
  owed/owing color meaning. A **net balance** reads the same everywhere it appears
  (balances view uses the modifiers via `[class.dc-amount--owed]` etc.); a **plain
  total** (an expense amount, a recorded payment) uses `.dc-amount` with no modifier
  (neutral bold ink). Reuse these classes rather than re-deriving the color logic.

## How to Run

Prerequisites: Java 21, Node 22+, Docker Desktop (must be running — needed for
the dev database AND for backend tests, which use Testcontainers).

1. **DB**: `docker compose up -d` (from repo root). Postgres 16 on host port
   **5433** (not 5432 — this machine runs a system Postgres on 5432).
   DB/user/password: `spliteasy`/`spliteasy`/`spliteasy`.
2. **Backend**: `cd backend && ./mvnw spring-boot:run` → port **8080**.
   Flyway migrations (`backend/src/main/resources/db/migration`) run on startup.
3. **Frontend**: `cd frontend && npm start` (first time: `npm install`) → port
   **4200**. Dev server proxies `/api/*` to `localhost:8080` (proxy.conf.json),
   so the app calls relative `/api/...` URLs with no CORS in dev.

Health check: `curl http://localhost:8080/api/health` →
`{"status":"UP","service":"spliteasy",...}` (`service` is `spring.application.name`;
`HealthController` builds this directly — there is no `HealthService`). The frontend
toolbar shows "● UP" when the full chain works.

CORS allowed origins are configurable via `app.cors.allowed-origins` (comma-separated;
dev default `http://localhost:4200`) — override with the real frontend origin in non-dev.

Auth/JWT config: the backend signs HS256 tokens with `app.jwt.secret`.
There is **no fallback default** — the dev secret lives in
`application-dev.properties` (active via the default `dev` profile), so
local runs work out of the box, but **any non-dev environment MUST set
`APP_JWT_SECRET` (>= 32 chars)** or the app fails to boot. Tokens carry
`iss` = `app.jwt.issuer` (default `spliteasy`), and the resource-server
decoder validates that issuer, so signing and validation can't drift.
Token lifetime is `app.jwt.expiration-seconds` (default 3600).

Quick API smoke test (backend running):
```
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"a@b.com","password":"password123","displayName":"A"}' | jq -r .accessToken)
curl -s -X POST localhost:8080/api/groups -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Trip"}'
```

Tests:
- Backend: `cd backend && ./mvnw test` (spins up a throwaway Postgres via
  Testcontainers; no docker-compose needed, but Docker must be running)
- Frontend: `cd frontend && ng test --watch=false` (Vitest, headless)

Troubleshooting (seen on this machine):
- esbuild dying with `spawn Unknown system error -88` / exit 137: the npm-installed
  binary had a corrupt code signature. Fix: `rm -rf node_modules/@esbuild node_modules/esbuild && npm install`.

## How to Validate a Change End-to-End

*(To be refined as we build. Baseline expectations below.)*

- Backend: `./mvnw test` must pass; for any new endpoint, show actual curl/HTTP
  request+response evidence, not just unit test pass/fail
- Frontend: `ng test` must pass; for any new UI flow, describe or screenshot the
  actual interaction (e.g., "created group X, added expense Y, verified split
  shown in UI")
- Money/split logic specifically: must include edge case tests — uneven splits,
  rounding, single-person groups, zero-amount expenses

## Conventions

*(Agents: when you make a design decision or the human corrects you, add a
line here so it's not relitigated next time.)*

- Spring Boot **4.1.0** (Initializr default). Boot 4 gotchas: starters are
  modular (`spring-boot-starter-webmvc`, per-starter `-test` artifacts);
  `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`;
  Testcontainers 2.x artifacts are `testcontainers-postgresql` /
  `testcontainers-junit-jupiter` (old unprefixed names won't resolve).
- Migrations: **Flyway** (chosen over Liquibase). `spring.jpa.hibernate.ddl-auto=validate` —
  Flyway owns the schema, Hibernate only validates.
- Backend tests that need a DB use **Testcontainers** (`TestcontainersConfiguration`
  in `backend/src/test/java`), never the docker-compose DB.
- Docker Postgres maps to host **5433** because a system Postgres occupies 5432.
- Frontend is Angular **21** (`ng new` current); unit tests run on **Vitest**
  (Angular's current default), not Karma. Angular Material not added yet — still TBD.
- Frontend HTTP goes through the shared `ApiService`
  (`frontend/src/app/core/api/api.service.ts`) with base path `/api`; one
  resource service per backend resource (see `health.service.ts` as the pattern).
- Backend CORS allows `http://localhost:4200`, exposed as a `CorsConfigurationSource`
  bean (`WebConfig`) and wired into the Spring Security chain via `http.cors()` —
  MVC-level CORS mappings run too late once Security is on the classpath.
- **Jackson 3** ships with Spring Boot 4: JSON classes live under `tools.jackson.*`
  (e.g. `tools.jackson.databind.ObjectMapper`), NOT `com.fasterxml.jackson.*`
  (annotations remain `com.fasterxml.jackson.annotation`).
- **UUID primary keys** for all entities (Hibernate `@UuidGenerator`, generated
  app-side so it works under `ddl-auto=validate`). Postgres column type `uuid`.
- **Auth**: JWT is HS256 via oauth2-resource-server. Tokens are issued in
  `JwtService` (claims: `sub`=user UUID, `email`, `displayName`) and validated by
  the auto-configured resource-server filter (`NimbusJwtDecoder` bean in
  `SecurityConfig`). Controllers read the caller as `@CurrentUserId UUID userId`
  (a custom argument resolver in `config/`; see below) — not raw `Jwt`.
  Passwords hashed with BCrypt; entities are NEVER serialized — DTOs only.
- **Group authorization is flat**: any member can view a group, add members
  (by email), and **edit or delete any expense** in the group (matches Splitwise;
  no creator/payer-only check). Room for roles later via `GroupMembership`.
- **Membership check before existence check, uniformly across all services**: every
  group-scoped operation calls `membershipGuard.requireMember(groupId, userId)` (→ 403)
  *before* loading the group by id (→ 404). A non-member therefore gets **403 for a
  nonexistent group id too**, so the API never reveals whether a group exists to
  someone who isn't in it. `GroupService`, `ExpenseService`, `BalanceService`, and
  `PaymentService` all follow this order.
- **Shared auth/util components — reuse these, don't re-inline them:**
  - `MembershipGuard` (`service/`): the single source of truth for the group-membership
    check. Wraps `GroupMembershipRepository` and throws `ForbiddenException` (→ 403,
    message "You are not a member of this group"). Inject it into any new group-scoped
    service instead of copying the `existsByGroupIdAndUserId` check.
  - `@CurrentUserId UUID` (`config/CurrentUserId` + `CurrentUserIdArgumentResolver`,
    registered in `WebConfig`): binds a controller `UUID` param to the JWT `sub`. Use it
    on new endpoints instead of `@AuthenticationPrincipal Jwt` + `UUID.fromString(...)`.
  - `Emails.normalize(String)` (`util/`): trim + lowercase, the one way emails are
    canonicalized before lookup/storage. Used by `AuthService` (register/login) and
    `GroupService.addMember`; reuse it anywhere an email is matched.
- **Dependency injection uses manual constructors over final fields, not Lombok.**
  This is a deliberate choice — Java 21 records already eliminate most DTO
  boilerplate, and the codebase is small enough that Lombok wouldn't earn its keep.
  New services should follow this pattern.
- **Expense edit = full-replace `PUT`** (`/api/groups/{g}/expenses/{id}`, reuses
  `CreateExpenseRequest`): shares are **always recomputed** via the same `SplitStrategy`
  path as create (`ExpenseService.computeSplit`) — never left stale. On update the
  participants are cleared and the orphan deletes are **flushed before re-inserting**
  (else the new rows collide with the old on the `(expense_id, user_id)` unique key).
- **Expense delete is a hard delete** (`DELETE` → 204); `orphanRemoval`/FK-cascade
  removes `expense_participants`. No soft-delete/`deleted_at` — nothing references a
  removed expense yet (no settle-up/history). Balances need **no special-casing**:
  they re-aggregate current rows, so edits/deletes are reflected automatically.
- **Avoid N+1 on collections**: fetch members with `join fetch m.user`; compute
  group member counts with one aggregate query (see `GroupMembershipRepository`).
  Expense lists use one query too: payer joined + participant count as an inlined
  correlated subquery (`ExpenseRepository.findSummariesByGroupId`); expense detail
  uses `join fetch` for payer, group, and participants+users.
- **Money is integer cents end-to-end** (`long` / Postgres `BIGINT`), never float.
  Frontend converts dollars→cents with `Math.round(x*100)` (`dollarsToCents`) and
  never sends decimals over the wire (requests carry `amountCents`). Incoming
  `amountCents` is bounded `@Positive @Max(1_000_000_000_000L)` (≤ $10B) on
  `CreateExpenseRequest` and `RecordPaymentRequest` — over-cap values → 400,
  guarding against griefing and `sum(...)` aggregate overflow.
- **Split calculation uses the Strategy pattern** via the `SplitStrategy` interface
  (`com.spliteasy.service.split`): `EqualSplitStrategy`, `UnequalSplitStrategy`,
  `PercentageSplitStrategy`, each a `@Component` returning its `SplitType`.
  `ExpenseService` selects one via a `Map<SplitType, SplitStrategy>` built from the
  injected strategy beans — **no if/else or switch on split type in the service**.
  **New split types must implement this interface, not add branching to the service.**
  Each strategy owns its own validation and is unit-testable with `new XStrategy().split(ctx)`
  (no Spring/DB). The service still resolves group membership before calling the strategy.
- **Build a `SplitContext` via its factory methods, not the raw constructor**:
  `SplitContext.forEqual(total, payer, participantIds)` for EQUAL, and
  `SplitContext.forSplits(total, payer, splits)` for UNEQUAL/PERCENTAGE. Each nulls the
  field the other kind doesn't use, so callers can't set both (or the wrong one).
- **`100%` in basis points is the named constant `TOTAL_BASIS_POINTS = 10_000`** — on the
  backend (`PercentageSplitStrategy`) and frontend (`expenses/expense.service.ts`). Use it
  instead of the bare `10000` literal; it documents the wire contract on both sides.
- **Rounding** (shared by EQUAL and PERCENTAGE via `SplitStrategy.absorbRemainder`):
  each participant gets the integer floor of their share; the leftover cents are absorbed
  by the **payer** when the payer is a participant, else the first participant in id-sorted
  order. Shares always sum back to the total exactly.
- **Split-type semantics** (`CreateExpenseRequest.splitType`, default EQUAL for back-compat):
  UNEQUAL carries a `splits` list of per-participant **cents** — rejected (400,
  `BadRequestException`) if they don't sum to `amountCents`. PERCENTAGE carries a `splits`
  list of **basis points** (hundredths of a percent, so 2-decimal %) — must sum to 10000.
  Values go on the wire as integers (cents / basis points); the frontend converts with
  `dollarsToCents` / `percentToBasisPoints` and shows a live running total to gate submit.
  Validation errors return clear messages, never 500s.
- Frontend: Angular Material (v21, CSS-based animations — `@angular/animations`
  is NOT a dependency, so don't add `provideAnimations*`). Auth token is attached
  and 401s handled by `authInterceptor`; authenticated routes use `authGuard`.
  Vitest is the test runner — use `vi.spyOn(...).mockReturnValue(...)`, not
  Jasmine's `spyOn(...).and.returnValue(...)`.
- **UI Design ("Evenly")**: the auth + groups screens follow a finished visual
  design imported from Claude Design (`design/evenly.dc.html`; source project
  `claude.ai/design/p/c15b1c41-…`). It's an editorial system — **Instrument Serif**
  display + **Hanken Grotesk** UI, lime accent `--accent` (`#B6F531`) with near-black
  `--on-accent` text, near-white green-tinted `--bg`, **sharp corners** (`border-radius: 0`)
  on all controls, 16px cards, circular avatars. Balances use color to signal owed/owing:
  green `--pos` / pink `--neg` (small balance text uses the darkened `--pos-strong` /
  `--neg-strong` for ≥4.5:1 contrast). All values live in
  `frontend/src/app/styles/_tokens.scss` (see Architecture). These screens use plain
  semantic HTML styled from the tokens rather than Angular Material components, because
  Material's opinionated inputs/buttons can't match the flat design faithfully; Material's
  `--mat-sys-*` colors are remapped to the tokens in `styles.scss` so the still-Material
  expense/balance/settle panels stay coherent. The app toolbar is hidden on `/login`
  and `/register` (full-bleed auth).

## Data Model (evolving)

Migration `V2__users_and_groups.sql`. All PKs are `uuid`.

- **users** — `id`, `email` (unique, not null), `password_hash` (not null),
  `display_name` (not null), `created_at`.
- **groups** — `id`, `name` (not null), `created_by` → `users.id` (not null),
  `created_at`.
- **group_memberships** — `id`, `group_id` → `groups.id` (ON DELETE CASCADE),
  `user_id` → `users.id`, `joined_at`. Unique `(group_id, user_id)`.
  Indexed on both `user_id` and `group_id`.

Migration `V3__expenses.sql`:

- **expenses** — `id`, `group_id` → `groups.id` (ON DELETE CASCADE),
  `description` (not null), `amount_cents` BIGINT (not null, CHECK > 0),
  `paid_by` → `users.id` (not null), `split_type` TEXT (not null, default
  `'EQUAL'` — enum `EQUAL`/`UNEQUAL`/`PERCENTAGE`, migration V4), `created_at`.
  Indexed on `group_id`.
- **expense_participants** — `id`, `expense_id` → `expenses.id` (ON DELETE
  CASCADE), `user_id` → `users.id`, `share_cents` BIGINT (not null, CHECK >= 0).
  Unique `(expense_id, user_id)`. Indexed on `expense_id` and `user_id`.

Migration `V5__payments.sql`:

- **payments** — `id`, `group_id` → `groups.id` (ON DELETE CASCADE),
  `payer_id`/`payee_id` → `users.id` (not null), `amount_cents` BIGINT
  (not null, CHECK > 0), `created_at`. `CHECK (payer_id <> payee_id)`.
  Indexed on `group_id`, `payer_id`, `payee_id`. A **settle-up payment** is a
  direct transfer between two members — a *separate* entity from Expense (no
  participants/shares), not a special expense type.

Relationships: User ↔ Group is many-to-many **through group_memberships**;
Group → User (created_by) is many-to-one. Creating a group auto-inserts a
membership for the creator (so the creator is a member, not a special case).
An Expense belongs to one Group, has one payer (User), and splits across a
subset of the group's members via expense_participants; the participant
`share_cents` always sum exactly to the expense `amount_cents`. **All three
split types (EQUAL/UNEQUAL/PERCENTAGE) store their result in the same
`share_cents` column** — there is no per-type storage — so balances (which read
only `share_cents`) work identically regardless of split type.

**Balances are derived, not stored.** A member's net balance for a group is
computed on read as `sum(amount_cents they paid) − sum(share_cents they owe)`
(positive = owed, negative = owes). `BalanceService` reads the already-persisted
expense/participant rows via two aggregate `GROUP BY` queries
(`ExpenseRepository.sumPaidByGroup`, `ExpenseParticipantRepository.sumOwedByGroup`)
— a fixed number of queries regardless of expense count, never a per-expense loop.
Because each expense's shares sum to its amount, a group's balances always sum to
zero (enforced as a test invariant). No new table; nothing recomputes the split.

**Payments (settle-up) net into balances through the same path**, not as a special
case. The formula is `net = expensePaid − expenseOwed + paymentsMade − paymentsReceived`;
a payment `payer → payee` of X does `payer.net += X` (payer now owes less) and
`payee.net −= X` (payee is owed less), via two more aggregate `GROUP BY` queries
(`PaymentRepository.sumPaidByGroup` / `sumReceivedByGroup`). Each payment contributes
`+X` and `−X`, so the **zero-sum invariant still holds** with expenses and payments
mixed. **Overpayment is allowed** (people prepay/overpay/round; the model is net-per-
person, not pairwise, so a "can't exceed what you owe" rule is ill-defined) — it simply
flips balances past zero. Validation: `amount_cents > 0`, `payer ≠ payee`, both members,
requester is a member.

**Debt simplification is a derived, read-only computation over balances** — it does NOT
re-aggregate anything. `DebtSimplificationService` reads `BalanceService.computeBalances(...)`
(so membership auth is inherited → 403 for non-members) and runs a **greedy
max-creditor/max-debtor** match: repeatedly settle `min(largest creditor, largest debtor)`
as one `debtor → creditor` transfer until everyone is zero. It's a near-optimal heuristic
(exact minimum-transaction is NP-hard), yields ≤ N−1 transfers, excludes zero-balance members
(no `$0` transactions), and — because balances are zero-sum integer cents — settles everyone to
exactly zero with **no leftover cents**. `GET /api/groups/{id}/debt-simplification` →
`SimplifiedDebtsResponse { groupId, transactions: [{ from, to, amountCents }] }`. Nothing is
persisted (acting on a suggestion records a normal `Payment`). Frontend: a "Simplify debts"
panel whose per-row "Settle up" reuses the settle-up form via the **`SettlePrefill` discriminated
union** (`{ kind: 'balance', … }` from a balance row vs `{ kind: 'transaction', payerUserId,
payeeUserId, amountCents }` from a suggestion — the latter seeds payer/payee/amount directly, no
current-user inference).

## Domain Rules to Remember

- All monetary amounts stored as integer cents (or BigDecimal with fixed
  scale) — never floating point.
- Every expense split must sum exactly to the total expense amount (handle
  rounding remainders deterministically, e.g., first payer absorbs the cent).

## TODOs / Known Gaps

- **Refresh tokens**: not implemented. Access tokens (1h) only; when they expire
  the user must log in again. Add a refresh-token flow before any real deployment.
- **Register reveals email existence** (409 on duplicate). Fine for now; revisit
  if enumeration becomes a concern.

## Git Workflow

- One branch per feature: `feat/<short-name>`
- Conventional commit messages (`feat:`, `fix:`, `test:`, `docs:`, `refactor:`)
- Before opening a PR: run full backend + frontend test suites, confirm no
  lint errors, include a "Testing" section in the PR description with
  end-to-end evidence

## Progress Log

*(Append one line per completed feature, most recent last. This is how we
track what's built so future planning doesn't duplicate or conflict.)*

- [x] Project skeleton (backend + frontend scaffolding) — done (branch
  `feat/project-skeleton`): Spring Boot 4.1/Java 21/Maven layered backend with
  Postgres+Flyway, docker-compose Postgres (host port 5433), Angular 21
  standalone frontend with shared ApiService, `/api/health` endpoint verified
  end-to-end (browser → proxy → backend → 200, page shows "Backend: UP");
  backend 2/2 and frontend 4/4 tests passing.
- [x] User & group management — done (branch `feat/user-group-management`):
  User/Group/GroupMembership entities (UUID PKs, migration V2), Spring Security +
  JWT (HS256 via oauth2-resource-server), register/login issuing access tokens,
  group create / add-member-by-email / list-my-groups / get-details with member
  authorization (403 for non-members). Angular Material UI: login/register,
  group list+create, group detail+add-member, auth interceptor + route guard.
  Verified end-to-end (curl: register/login/create/add/list/details incl. 401/403/404;
  browser: registered Dave, created "Weekend Cabin", added Alice → 2 members).
  Backend 19/19, frontend 16/16 tests passing.
- [x] Add expense with equal split — done (branch `feat/expense-equal-split`):
  Expense + ExpenseParticipant entities (UUID PKs, migration V3), pure
  `ExpenseSplitCalculator` (payer absorbs remainder cents, integer cents only),
  endpoints to create / list / get expenses under `/api/groups/{id}/expenses`
  (member-only; 403 for non-members, 400 for bad amount/payer/participant).
  Angular expense panel (add form with payer select + participant checkboxes,
  expense list) embedded in the group detail page. Verified end-to-end (curl:
  $10.00/3 → 334/333/333; browser: $10.01/2 → Alice 501 / Dave 500, summing to
  1001) and confirmed the list endpoint issues a single SQL query (no N+1).
  Backend 41/41, frontend 21/21 tests passing.
- [x] Group balances view — done (branch `feat/group-balances`): `BalanceService`
  derives each member's net position from stored expenses via two aggregate GROUP
  BY queries (no per-expense loop, no new table); `GET /api/groups/{id}/balances`
  (member-only, 403 otherwise). Angular balance panel in the group view
  ("is owed $X" / "owes $X" / "settled up"), auto-refreshing when an expense is
  added. Verified end-to-end (3 expenses across 3 payers → Alice +$12 / Bob −$3 /
  Carol −$9, sum 0; live-refresh to +$16 / −$5 / −$11 after a 4th). Zero-sum
  invariant asserted in tests. Backend 48/48, frontend 26/26 tests passing.
- [x] Unequal & percentage splits — done (branch `feat/exact-percentage-splits`):
  `split_type` enum on Expense (migration V4, default EQUAL); UNEQUAL/PERCENTAGE add
  a `splits` list to `CreateExpenseRequest` (cents / basis points) while the equal
  path and its tests are untouched (added a delegating 4-arg constructor). Service
  rejects (400) sums that don't match the total / 100%; PERCENTAGE reuses the
  payer-absorbs-remainder rounding. All types store `share_cents`, so balances are
  unchanged. Frontend adds an Equal/Unequal/Percentage toggle with per-participant
  value inputs and a live running-total gate. Verified end-to-end (UNEQUAL $20 →
  1200/500/300; PERCENTAGE 33.33/33.33/33.34% of $10 → 333/334/333 payer-absorbs;
  bad sums → 400; mixed-split balances still net to zero). Backend 59/59, frontend
  27/27 tests passing.
- [x] Split-strategy refactor — done (branch `refactor/split-strategy-interface`):
  replaced the `ExpenseSplitCalculator` static util + service `switch` with a
  `SplitStrategy` interface and `Equal`/`Unequal`/`PercentageSplitStrategy` beans,
  selected via a `Map<SplitType, SplitStrategy>` in `ExpenseService`. Behavior
  unchanged — the higher-level expense/balance integration tests pass **unmodified**;
  the calculator's unit tests were relocated to per-strategy tests with identical
  assertions (+ new UNEQUAL strategy tests). Verified via real API that all three
  types produce identical shares/messages as before. Backend 62/62 passing.
- [x] Edit & delete expenses — done (branch `feat/edit-delete-expense`): `PUT`
  (full-replace, recomputes shares via the shared `SplitStrategy` path — no parallel
  math) and `DELETE` (hard delete → 204) under `/api/groups/{g}/expenses/{id}`,
  member-only (403 for non-members, 404 wrong-group). On edit the participants are
  cleared + flushed before re-insert (orphan-removal ordering). Frontend reuses the
  add-expense form for edit (prefilled; UNEQUAL exact, PERCENTAGE best-effort from
  cents) and deletes behind an inline "Delete? Yes/No" confirm; balances refresh via
  the renamed `expensesChanged` output. `BalanceService` unchanged — balances
  re-aggregate, so edits/deletes reflect automatically (zero-sum verified after both).
  Verified end-to-end (create → edit $9 equal → $30 percentage recompute → delete →
  gone from list + balances). Backend 69/69, frontend 29/29 tests passing.
- [x] Settle up (record a payment) — done (branch `feat/settle-up`): separate
  `Payment` entity (migration V5) + `POST`/`GET /api/groups/{g}/payments`, member-only.
  Payments net into balances through the same aggregate path as expenses
  (`BalanceService` gains two GROUP BY terms: `payer.net += X`, `payee.net −= X`) — no
  special-casing; zero-sum holds with expenses + payments mixed. Overpayment allowed
  (flips the sign). Frontend: per-row "Settle up" on the balances view prefills the
  payment form (direction by sign), a confirm step ("Record that Bob paid Alice $8?"),
  and a payment history list; balances refresh via the shared refresh key. Verified
  end-to-end (Bob settles $10 → pair hits exactly 0; Carol overpays $15 → sign flips;
  zero-sum throughout; UI prefill → confirm → balances "settled up" + history row).
  Backend 78/78, frontend 31/31 tests passing.
- [x] Debt simplification — done (branch `feat/debt-simplification`): read-only
  `GET /api/groups/{g}/debt-simplification` → minimal `{ from, to, amountCents }` transfers.
  `DebtSimplificationService` reuses `BalanceService.computeBalances` (no duplicated
  aggregation; membership auth inherited → 403) and runs a **greedy max-creditor/max-debtor**
  match (near-optimal heuristic, ≤ N−1 transfers, zero-balance members excluded, no leftover
  cents). Frontend: a "Simplify debts" panel; each suggestion's "Settle up" seeds the existing
  settle-up form via the new `SettlePrefill` union (`kind: 'transaction'`). Verified end-to-end
  (3 non-zero balances → 2 transfers summing to the total debt; UI: clicked "Carol pays Alice
  $4.00" → form prefilled Carol→Alice→$4 → recorded → Carol settled, suggestion recomputed to
  just "Bob pays Alice $1.00"). Backend 93/93, frontend 33/33 tests passing.
