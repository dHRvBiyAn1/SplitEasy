# AGENTS.md — SplitEasy (Splitwise clone)

Project living memory. **Read before a task; update after one** — especially
Conventions, Data Model, and the Progress Log (append one line per shipped feature).

## Overview

Expense-splitting web app: users create groups, add expenses split among members
(equal / exact / percentage), and the app tracks who owes whom, plus settle-up
payments and greedy debt-simplification suggestions. Balances are
**derived on read**, never stored.

## Stack

- **Backend**: Java 21, Spring Boot 4.1, Maven — layered `controller → service → repository → entity`.
- **DB**: PostgreSQL 16, Spring Data JPA + **Flyway** migrations (`ddl-auto=validate`).
- **Frontend**: Angular 21 standalone components + signals; **Vitest** tests. Angular
  Material present but only its `--mat-sys-*` colors are remapped to tokens — screens use
  plain semantic HTML.
- **Auth**: Spring Security + JWT (HS256, symmetric) via `oauth2-resource-server`. Access
  tokens only (1h), intended re-login on expiry; client logout does not revoke tokens.
  Contract and future requirements: [session lifecycle](docs/security/session-lifecycle.md).

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

## Production Deployment

- Angular is served by Cloudflare Pages at `https://split-easy.io`; Java and PostgreSQL run on the existing AWS Lightsail Sydney instance (user-confirmed `ap-southeast-2`). `api.split-easy.io` routes through remotely managed Cloudflare Tunnel → private HTTP Caddy → Java. `compose.prod.yml` has no public application ports; PostgreSQL is on its own internal network. Verified SSH on port 22 remains the deployment path. Runbook: `docs/deployment/lightsail.md` (beginner steps for each console/terminal).
- Cloudflared runs as UID 65532 with a private server-only token file (`TUNNEL_TOKEN_FILE`, mode 0400, owner 65532) mounted read-only. Production Caddy/cloudflared images are digest-pinned. Caddy preserves `/api`, rejects all other paths, and sets API no-store; Cloudflare supplies public HTTPS.
- Angular local environment uses `/api`; production file replacement uses `https://api.split-easy.io/api`. `apiPath` normalizes URL origin/path boundaries; `authInterceptor` attaches JWT and handles 401 only for trusted non-auth API routes. Prod CORS allows exactly `https://split-easy.io`; previews are not implicitly trusted.
- `.github/workflows/ci-cd.yml`: PR/dev checks, Node 22 Pages artifact, real backend/Caddy/Postgres smoke, main-only GHCR backend digest publication. Serialized production delivery requires repository `DEPLOY_ENABLED=true`, production environment SSH settings, Cloudflare account/project variables and Pages Edit token. Backend/tunnel health succeeds before Pages upload. `deploy/deliver.sh` implements that ordering and skips superseded main revisions. No AWS API keys required for delivery.
- Releases use a single backend digest manifest and unique directories. Retain host lock, pre-migration pg_dump, physical release paths, failed-release pointer protection and schema-aware rollback. PostgreSQL volume identity remains unchanged. Pages and backend publication are not atomic; Pages failure leaves the healthy backend in place.
- `deploy/compose.ci.yml` is disposable-only: Caddy port on 127.0.0.1:18080, cloudflared disabled behind a profile. Never use this override or `down --volumes` on production.
- Daily off-host backups are configured separately; `deploy/backup.sh` supports exported `BACKUP_S3_URI`. A value placed only in Compose .env is not automatically exported to the backup script. Existing application review gaps/refresh tokens remain outside this deployment migration.
- CI and frontend verification use Node 22. Node 26 experimental Web Storage interferes with jsdom/Vitest localStorage; use the validated runtime.
- First production release fcf12e6 is live: backend/tunnel and Pages activated; public API health and Angular deep links verified. Main run38649436260 succeeded on attempt2 after a transient previous-HTML check failure. Follow-up propagation retry change remains subject to dev/main promotion.

## API

All under `/api`; all except `auth/*` and `health` require `Authorization: Bearer <jwt>`.
Group-scoped routes are member-only (**403 before 404** — see Conventions).

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register`, `/auth/login` | issue access token |
| GET | `/health` | liveness |
| GET | `/dashboard` | one aggregated payload for the shell + landing page |
| GET/POST | `/groups` | list mine / create |
| GET/PATCH/DELETE | `/groups/{g}` | detail (members) / partial update / delete (**creator only**) |
| POST | `/groups/{g}/members` | add by email |
| DELETE | `/groups/{g}/members/{m}` | remove a member — or leave, when `{m}` is you (409 if unsettled) |
| GET/POST | `/groups/{g}/expenses` | list summaries / create |
| GET/PUT/DELETE | `/groups/{g}/expenses/{e}` | detail / full-replace edit / hard delete (204) |
| GET | `/groups/{g}/balances`, `/groups/{g}/balances/mine` | net-per-member / pairwise to me |
| GET/POST | `/groups/{g}/payments` | settle-up history / record |
| GET | `/groups/{g}/debt-simplification` | greedy transfer suggestions |

## Architecture

- DTOs at the controller boundary — **entities are never serialized**. DTOs are Java
  records grouped in sub-packages under `com.spliteasy.dto`: `auth/ group/ expense/
  payment/ balance/ dashboard/ common/`.
- Frontend: one Angular service per backend resource; all HTTP via `core/api/api.service.ts`
  (local base `/api`, production `https://api.split-easy.io/api`); `authInterceptor` attaches the token + handles 401 for trusted API requests; `authGuard` on private routes.
- **UI tokens live in one place**: `frontend/src/app/styles/_tokens.scss` (colors, type,
  spacing, motion as CSS vars) + `_ui.scss` (`.dc-*` primitives). Both `@use`d once in
  `styles.scss`. Reference `var(--…)` / `.dc-*` — never hardcode. Selected design ref `docs/concepts/ui-redesign/objects.html`.

## Backend Conventions

- **Spring Boot 4.1 gotchas**: modular starters (`spring-boot-starter-webmvc`, per-starter
  `-test`); `@WebMvcTest` in `org.springframework.boot.webmvc.test.autoconfigure`;
  Testcontainers 2.x artifacts `testcontainers-postgresql`/`-junit-jupiter`. **Jackson 3**:
  classes under `tools.jackson.*` (annotations still `com.fasterxml.jackson.annotation`).
- **Lombok is used** (PR #22): entities `@Getter @Setter @NoArgsConstructor(access =
  AccessLevel.PROTECTED)` + business constructors; controllers/services `@RequiredArgsConstructor`
  (exception: `HealthController` keeps its `@Value` ctor). DTOs stay records.
- **UUID PKs** everywhere (`@UuidGenerator`, app-side so it works under `validate`; Postgres `uuid`).
- **Auth abuse limits**: login 30/IP/5min and 5/(normalized email, IP)/5min; registration 20/IP/hour (`APP_AUTH_REGISTRATION_LIMIT`). Atomic process-local fixed windows cap at 10k buckets; restart resets, share enforcement before multiple instances. Prod native Caddy/Tomcat forwarding relies on private Tunnel→Caddy→Java networks; direct dev ignores forwarded headers. See `docs/security/auth-abuse.md`; 429 includes CORS-exposed Retry-After and both forms retain session state.
- **Auth**: tokens issued in `JwtService` (claims `sub`=user UUID, `email`, `displayName`),
  validated by the resource-server filter (`NimbusJwtDecoder` in `SecurityConfig`). Read caller as
  `@CurrentUserId UUID` (resolver in `config/`) — not raw `Jwt`. Passwords BCrypt.
- **Reuse these, don't re-inline**: `MembershipGuard.requireMember(groupId,userId)` (the group-membership
  check → 403); `@CurrentUserId`; `Emails.normalize()` (trim+lowercase, canonical form for lookup/storage).
- **403 before 404, uniformly**: every group-scoped op calls `requireMember` *before* loading by id,
  so a non-member gets 403 even for a nonexistent group (API never reveals existence). Followed by
  Group/Expense/Balance/Payment services.
- **Group authz is flat, with one exception**: any member can view, rename, change settings, add
  members, remove a settled member, and **edit/delete any expense** (matches Splitwise). The
  exception is **deleting the group — creator only** (`groups.created_by` is the closest thing to an
  admin; there is no role column). The creator can also neither be removed nor leave, so a group
  can't end up with nobody able to delete it.
- **Group PATCH writes only dirty settings columns** (`Group` uses Hibernate `@DynamicUpdate`), so
  overlapping partial edits preserve omitted fields. Same-field edits have no version check: the
  last committed dirty write wins; assigning the loaded value is a no-op. PATCH responses reflect
  the transaction's loaded entity; use a fresh GET to observe other concurrent commits.
- **Removing a member / leaving needs a settled balance**: blocked with **409** while that member's
  **net in the group** is non-zero, so no debt is orphaned. The net comes from
  `BalanceService.computeBalances` — never a second aggregation.
- **Payment retries**: optional UUID `operationId` is unique per authenticated requester/group (V9). Authorize membership before replay lookup; identical payload returns original 201, changed payer/payee/amount 409. Keyed writes use native PostgreSQL `ON CONFLICT DO NOTHING` plus a READ COMMITTED lookup, never catch uniqueness and query an aborted transaction. Missing/null IDs preserve additive legacy POSTs. Contract and client in-memory retry limits: `docs/api/payment-retries.md`.
- **Money = integer cents end-to-end** (`long`/`BIGINT`), never float. `amountCents` bounded
  `@Positive @Max(1_000_000_000_000L)` (≤$10B) on create-expense/record-payment → 400 over cap.
- **Splits use the Strategy pattern** (`service.split`): `Equal`/`Unequal`/`PercentageSplitStrategy`
  `@Component`s selected via `Map<SplitType, SplitStrategy>` in `ExpenseService` — **no switch on type**.
  New types implement the interface. Build inputs via `SplitContext.forEqual(...)` / `.forSplits(...)`
  factories (never the raw ctor). `100%` = `TOTAL_BASIS_POINTS = 10_000`. Rounding
  (`SplitStrategy.absorbRemainder`): floor each share, **payer absorbs leftover cents** (else first
  id-sorted participant); shares always sum to the total. Exact inputs must be `0..amountCents`;
  percentage inputs must be `0..10_000`. Strategies validate bounds and sum with `BigInteger`
  before computing shares; percentage multiplication uses quotient/remainder arithmetic to
  avoid overflow. POST/PUT reject invalid splits before changing expense or participant rows.
- **Expense edit = full-replace PUT** (reuses `CreateExpenseRequest`): shares recomputed via the same
  strategy path; participants cleared and **flushed before re-insert** (unique `(expense_id,user_id)`).
  **Delete = hard delete** (204; FK-cascade removes participants). Balances re-aggregate, so edits/deletes
  reflect automatically — no special-casing.
- **Detected concurrent writes**: Spring `OptimisticLockingFailureException` (including translated
  Hibernate stale-row failures) maps to **409** with reload/review guidance. Transactions roll back;
  clients never automatically retry the edit. No version/ETag contract guards stale separate GET/PUTs.
- **Indexes** live in Flyway migrations, named `idx_<table>_<cols>` (unique constraints
  `uq_<table>_<cols>`). Every FK column is indexed; **filter+sort paths get a composite
  `(filter_col, sort_col DESC…)`** whose leftmost prefix also serves the plain filter, so
  no redundant single-column index is kept alongside it (see V7 for expenses/payments).
- **Logging**: backend uses **Log4j2** (pom excludes `spring-boot-starter-logging`, adds
  `spring-boot-starter-log4j2`); code logs via the SLF4J API using Lombok `@Slf4j`. Level
  convention — **DEBUG** flow detail (dev only), **INFO** business events (register/login,
  group/expense/payment create·update·delete), **WARN** client errors (4xx, centralized in
  `GlobalExceptionHandler`), **ERROR** unexpected 5xx (the handler's catch-all, with stack
  trace). Appender/pattern in `log4j2-spring.xml`; levels via `logging.level.*` in
  `application[-dev].properties` (`com.spliteasy` = INFO prod / DEBUG dev). Never log secrets/tokens.
- **Raced duplicates**: keep friendly registration/member prechecks; the database owns uniqueness.
  After service rollback, `GlobalExceptionHandler` maps only SQLState `23505` with exact constraints
  `users_email_key` / `uq_group_memberships_group_user` to the same 409 messages. Other integrity
  failures remain server errors; never classify by exception message text.
- **Avoid N+1**: `join fetch m.user`; member counts via one aggregate query; expense list via one query
  (payer joined + count as correlated subquery, `ExpenseRepository.findSummariesByGroupId`); detail via `join fetch`.

## Frontend Conventions

- **Expense group ownership**: each member-load callback must match the current selection generation
  (including A → B → A). Clear members, payer and split inputs on selection; gate save on completed
  member/detail loads. Existing expense edits keep their original group in both UI and component actions.

- **Profile preferences belong to immutable auth user IDs**: phone, display currency, avatar color and
  first-visit date use `spliteasy.profile.<userId>`. Linked signals synchronously reload/reset saved prefs
  and profile edit state when identity changes, including before an immediate save. The ownerless legacy
  `spliteasy.profile` key is discarded without migration; users re-enter those personal fields. Theme and
  palette intentionally remain device-wide.

- **Account session ownership**: successful login/register and every logout call `SessionService.reset()`
  synchronously. Dashboard and global-modal services clear their state on that boundary; UI logout handlers
  call only `AuthService.logout()`. Dashboard callbacks must match the cache generation captured before the
  request; each refresh advances it so only the newest request owns data, error and loading. Protected
  401 logout/redirect must match the initiating session version (even if a new login returns the same token). Modal HTTP operations use `takeUntil(session.changes)` on the entire operation,
  after aggregation such as `toArray()`, so queued mutations and old callbacks cannot continue as a new user.
- **Custom expense splits**: cache the normalized outgoing cents/basis-point list and reuse it for
  the live total, validation and POST/PUT; reject negative/nonfinite/unsafe values before zero omission.
  Round each share to the nearest hundredth with existing conversion helpers and explain this in the form.

- Financial member controls use full display name plus email for accessible identity (including duplicate names),
  numeric split labels name dollars or percent, and selection buttons expose `aria-pressed`.

- **Payment operation ownership**: `PaymentService` retains one in-memory pending group/payer/payee/amount + UUID across uncertain errors and modal reopen. Reuse only for identical intent; success, a different submitted intent, or `SessionService.reset()` clears/replaces it. Old-session observables cannot post under a new account. Browser reload/cross-tab loses the identity; consult history after an uncertain response.
- Angular 21 standalone + signals; **no `@angular/animations`** (Material uses CSS animations — don't add
  `provideAnimations*`). Motion, where added, is CSS + tokens.
- New-group member additions retain the created group ID and per-email outcomes; failed-only retries reuse that group. Keep session cancellation after the entire sequential aggregation.
- Modal `<form>`s use `(submit)="$event.preventDefault(); save()"` — **not** `(ngSubmit)` — because modals
  use bare `[formControl]`s (no `formGroup`/`NgForm`), so a submit would otherwise reload the page.
- **`.dc-amount` is the single owed/owing color convention** (`--owed` green / `--owes` pink / `--settled`
  muted; small text uses darkened `--pos-strong`/`--neg-strong` for ≥4.5:1). A **net balance** uses the
  modifiers; a **plain total** (expense amount, payment) uses `.dc-amount` bare. Reuse — don't re-derive.

## Design System ("Pocket Objects") — implemented locally

The selected Pocket Objects design now covers every Angular route and global modal. Seafoam & Petrol
is the default palette, with Lilac & Plum and Peach & Cocoa alternatives. Rounded material objects,
locally bundled Nunito Sans display / DM Sans UI fonts, SVG group illustrations, restrained glass sheen
and shared CSS tokens replace the earlier Evenly presentation. The editable selected study lives at
`docs/concepts/ui-redesign/objects.html`; actual implementation evidence is in
`docs/reviews/2026-10-15-ui-redesign/implementation/verification.md`.

**Standing rule:** reuse `_tokens.scss`, `.dc-*` and `.dc-amount`; never reintroduce Material presentation
or duplicate money-status logic. `app-money` formats integer cents for the dashboard/tray, keeps plain
totals neutral, and makes unusually long amounts keyboard-scrollable with their full text available.
Auth routes remain full-bleed with a named Appearance control.

- **Shell + dashboard:** capsule header, Groups tray and a four-icon labelled Overview / Groups /
  Expense / Profile dock through 1024px. Active selection derives from route + modal; Expense is active
  only during composition. One cached `GET /api/dashboard` remains the data source. Mutations call
  `dashboard.refresh()`. Failed refresh retains only current-session trusted data with a retry notice;
  failed initial loads settle into the retry view instead of automatically looping.
- **Appearance:** `ThemeService` owns independent `spliteasy.palette` (`seafoam` default / `lilac` /
  `peach`) and existing `spliteasy.theme` (`system` default / `light` / `dark`). Explicit mode overrides
  OS CSS and sets native `color-scheme`; System follows live OS changes. Header and Profile use the
  same Appearance panel. Preferences are device-local, independent of account state.
- **Motion:** `MotionPreferenceService` owns `spliteasy.motion`; OS reduction always wins. One optional
  `RibbonService` canvas runs outside Angular with passive pointer/touch input. Silk Ribbon uses a
  precise SVG mouse tip, tapered trail, and brief touch bloom/trace. Clear on session reset, multi-touch,
  cancellation, blur/hidden state, reduction and teardown; native scroll/pinch stays available. Glass
  uses bounded shared CSS with reduced-motion/forced-color fallbacks. No new motion dependency.
- **Dialogs:** shared named `ModalShellComponent` uses CDK focus trapping/restoration, inert shell,
  body scroll locking, Escape and a scroll-contained mobile surface. Rendered content changes recover
  heading focus only when removal leaves focus on body; all background navigation, including the skip
  link, stays inside the inert shell. `ModalService` owns New group /
  New or edit expense / Settle up / Add member / Appearance; HTTP/session ownership stays in services.
- **Group pages:** route/account/destroy changes cancel owned callbacks, and a new reload cancels its
  predecessor. Group, balance and expense reads complete together before actions render. Reload clears
  displayed receipt details. Route navigation immediately clears the previous group's metadata,
  pairwise balances and expense history while the new group loads. Offsetting pairwise debts with net
  zero say “Even overall”, not “All square”.

## Data Model

UUID PKs throughout. Migrations in `backend/src/main/resources/db/migration`.

- **users** (V2) — `id`, `email` (unique), `password_hash`, `display_name`, `created_at`.
- **groups** (V2) — `id`, `name`, `created_by`→users, `created_at`; +`type` (V6: `HOME/TRIP/DINING/EVENT/OTHER`, default OTHER).
- **group_memberships** (V2) — `id`, `group_id`→groups (CASCADE), `user_id`→users, `joined_at`. Unique `(group_id,user_id)`; indexed both.
- **expenses** (V3) — `id`, `group_id`→groups (CASCADE), `description`, `amount_cents` BIGINT (>0), `paid_by`→users,
  `split_type` (V4: `EQUAL/UNEQUAL/PERCENTAGE`, default EQUAL), `created_at`; +`category` (V6, 8-value enum) +`spent_on` DATE (V6, user-chosen date ≠ created_at). Composite index `(group_id, spent_on DESC, created_at DESC)` (V7, serves the ordered list + feed); `paid_by` indexed.
- **expense_participants** (V3) — `id`, `expense_id`→expenses (CASCADE), `user_id`→users, `share_cents` BIGINT (≥0). Unique `(expense_id,user_id)`.
- **payments** (V5) — `id`, `group_id`→groups (CASCADE), `payer_id`/`payee_id`→users, `amount_cents` (>0), `created_at`. `CHECK(payer≠payee)`. Composite index `(group_id, created_at DESC)` (V7, serves the ordered history + feed); `payer_id`/`payee_id` indexed. A settle-up is a **direct transfer**, a separate entity from Expense. V9 adds nullable `recorded_by`→users and `operation_id` UUID, paired-null check and unique `(group_id,recorded_by,operation_id)`; legacy rows remain null, requester FK indexed separately.

User↔Group is many-to-many via memberships; creating a group auto-inserts the creator's membership. All three split types store into the **same `share_cents`** column (no per-type storage), and each expense's shares sum exactly to its amount.

## Balances & Splits (the math)

- **Balances are derived, not stored**: a member's net = `paid − owed + paymentsMade − paymentsReceived`,
  computed via a fixed number of aggregate `GROUP BY` queries (never a per-expense loop). Because shares sum to
  amounts, **group balances always sum to zero** (test invariant) — holds with expenses + payments mixed.
  **Overpayment allowed** (net-per-person model; flips sign past zero).
- **Ledger reads share a PostgreSQL `REPEATABLE_READ` snapshot**: `BalanceService`, both public pairwise
  methods, dashboard and simplification set isolation at their transaction boundaries. Any existing outer
  transaction must own that isolation too (`GroupService.removeMember` includes the settled-balance guard);
  an inner `REQUIRED` transaction cannot upgrade its caller. Read-only alone is insufficient. Concurrency
  regressions use real committed writes between aggregates, without a test-managed reader transaction.
- **Three views, one source of truth** (persisted expense/payment rows): (1) `BalanceService` net-per-member;
  (2) `DebtSimplificationService` greedy transfer suggestions; (3) `PairwiseBalanceService` who-owes-whom-to-me per
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

- Monetary amounts are integer cents — never float. JSON integer fields reject floating-point tokens
  through `spring.jackson.deserialization.accept-float-as-int=false` before DTO validation; split
  values are integer cents (UNEQUAL) or basis points (PERCENTAGE).
- Every split sums exactly to the expense total; rounding remainder handled deterministically (payer absorbs).

## Known Gaps / TODOs

- **Refresh tokens** — not implemented; 1h access tokens only, re-login on expiry. Add before real deploy.
- **Register reveals email existence** (409 on duplicate) — fine for now; revisit if enumeration matters.
- **Simplified settle-up is fetched per group** — `/debt-simplification` derives transfers from one
  group's balances, so the settle-up modal fans out one request per in-scope group
  (`DebtService.getSimplifiedDebtsForGroups`). Batch server-side if users ever join many groups.
- **Simplify-debts is a group setting, not a view toggle** (`groups.simplify_debts`, V8, default on).
  Settle up has no switch of its own: it reads each group's flag, so a dashboard-level settle-up can
  mix simplified and direct rows. The flag rides on the dashboard payload
  (`GroupSummary` → `DashboardGroup`) — anything changing it must call `dashboard.refresh()`, or the
  modal keeps reading the cached value.
- **Profile prefs are client-side only** (PR #23, #53) — phone/currency/avatar-color per account in
  localStorage; theme/palette per device. "First seen here" = first-visit month. No profile-update
  endpoint or signup-date column yet.

## Git Workflow

- Use `dev` for development; promote tested changes through a `dev` → `main` PR. `main` is the production branch. Conventional commit messages.
- Pushes to `dev` run CI only; production image publication and Lightsail deployment are restricted to `main`, with the server/secrets and `DEPLOY_ENABLED=true` configured first.
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
- [x] Structured logging (Log4j2) — swapped the backend to Log4j2 and added leveled logging
  (`@Slf4j`) across services + a centralized `GlobalExceptionHandler`: DEBUG flow, INFO business
  events, WARN 4xx, ERROR unexpected 5xx. Config in `log4j2-spring.xml` + `logging.level.*`.
  Backend 97/97 tests passing.
- [x] Query performance indexes — migration V7 (`V7__query_performance_indexes.sql`): composite
  `idx_expenses_group_spent_on (group_id, spent_on DESC, created_at DESC)` and
  `idx_payments_group_created_at (group_id, created_at DESC)` for the group list + dashboard
  feed + settle-up history; drops the now-redundant single-column `idx_expenses_group`/`idx_payments_group`. Validated against the live schema.
- [x] Group settings + shell polish — `feat/group-settings`: new `/groups/:id/settings` behind a gear
  in the header (Settle up | Add expense | gear), with inline rename, a members list carrying each
  balance and a removal gated on being settled, the per-group Simplify debts switch, and inline-confirm
  Leave / creator-only Delete. Backed by new group mutations (V8 `simplify_debts`, PATCH, member
  removal/leave, delete). Settle up lost its own toggle and follows the group. Sidebar is collapsible
  to an icon rail (persisted), glyphs became SVGs, and the old `/groups` list page is gone.
  Backend 107/107, frontend 50/50.
- [x] Settle-up simplification + UI fixes — `feat/settle-up-simplify-and-ui-fixes`: Settle up opens on
  the suggested netted payments with a Simplify switch back to every direct balance, scoped per
  group; amounts are editable so a balance can be settled partially; Add member moved into the group
  action row; dashboard member count centered; avatar/glyph colors made theme-aware via `--avatar-ink`
  (they were dark-mode-only, so light mode showed dark tiles). Frontend 50/50, backend 97/97.
- [x] Profile page + theme toggle — PR #23 (`feat/profile-page`): `/profile` route, sidebar "View profile" link, view/inline-edit (name/email/phone/currency/avatar-color), System/Light/Dark toggle via `ThemeService`. Prefs client-side only (see Gaps). Verified in browser.

- [x] Lightsail Docker + CI/CD — `feat/lightsail-deployment`: multi-stage Java/Angular-Caddy images, private PostgreSQL 16 production Compose, explicit prod JWT configuration and DB readiness, pinned GitHub Actions tests/builds/GHCR digest releases/verified SSH, locked pre-migration backups and schema-aware rollback runbook. Backend 108/108, frontend 50/50 on Node 22; container auth/deep-link/persistence and real backup restore verified; release tests and shell/workflow lint pass. Infrastructure implemented; remote deployment requires server/domain/secrets.

- [x] Development branch workflow (2026-10-15) — `dev` created from current `origin/main` (`7476c12`) and published; development uses `dev`, promotion uses `dev` → `main`. PR #28 adds dev-push CI while keeping image publication/production deployment restricted to main; Actionlint passed. Main activation still requires merging the deployment workflow and setting the Lightsail server/secrets.

- [x] Pages + Lightsail Tunnel deployment (2026-10-15) — split-easy.io Angular Pages artifact; api.split-easy.io Tunnel → private Caddy → Java/PostgreSQL on the existing Sydney instance. No published production app ports; server-only tunnel token, scoped API JWT handling and backend-first main-only delivery with verified SSH/backups. Backend 108, frontend 57, deployment methods 10; real CORS/auth/persistence/dump-restore, Caddy validation, Wrangler SPA preview and script/workflow lint passed. Beginner runbook includes separate ubuntu administration and deploy commands. Main merge and live activation remain pending; metadata-only SSH recheck confirms the user-populated tunnel token is nonempty, owner65532:65532/mode0400; connector authentication is unverified.

- [x] CI portability + dependency maintenance (2026-10-15) — reproduced/fixed Compose JSON false-field omission on Linux Compose2.39.2; retains unsafe-mount rejection. Consolidated pinned checkout7/setup-node7/setup-java6 into dev with Java21/Node22 unchanged; monthly Dependabot version updates grouped and directed to dev, Java major image bumps ignored. Backend108/frontend57 and deployment10 on Linux/local Compose, formatting/build/lint passed; PR34 CI verification and main promotion pending.

- [x] Production recovery + Pages propagation checks (2026-10-15) — main fcf12e6 deployed backend/tunnel/Pages, but immediate frontend HTML check failed. Verified public API/login/group routes, retried main run38649436260 and confirmed attempt2 success without DB restore/removal. Delivery now retries previous HTTP200 HTML as well as HTTP errors with bounded attempts and clear exhaustion errors. Red/green regressions and deployment12 on Linux/local Compose, ShellCheck/Actionlint, backend108/frontend57 formatting/build passed; follow-up PR/main promotion pending.

- [x] Account session isolation (#45, 2026-10-15) — shared synchronous session reset clears dashboard/modal state on login/register/logout; earlier-session dashboard callbacks and protected 401s are ignored, and pending modal reads/mutations (including queued member additions) cancel on identity changes. Regression-first HTTP/component coverage: initial14 failures and modal5 failures, then frontend80/80 (23 session cases); backend108/108, Prettier/production build, deployment12, ShellCheck/Actionlint and isolated backend/Caddy/Postgres auth/CORS/persistence smoke passed. Local browser verified Alice expiry → modal closed/login → Bob's own sidebar/group. Main PR pending; no merge/deployment.
- [x] Consistent derived balance snapshots (2026-10-15, #38) — `fix/38-balance-snapshots`: PostgreSQL repeatable-read boundaries cover balances, pairwise, dashboard/activity, simplification and the member-removal guard. Thirteen deterministic regressions coordinate real expense/payment commits (12 behavioral failures before the fix); backend121, frontend57, formatting/build, deployment12, ShellCheck/Actionlint passed. Isolated private Caddy/backend/PostgreSQL HTTP ledger and restart-persistence smoke passed; no schema change, merge to main or deployment.
- [x] Split overflow validation (#37, 2026-10-15) — bounded exact/percentage values, overflow-safe BigInteger sums and percentage arithmetic before shared POST/PUT persistence; preserves the 1e12 amount cap and deterministic payer remainder. Regression-first strategy/API coverage includes adversarial long inputs, unchanged rejected writes, and zero/full boundary shares. Backend125/frontend57, formatting/production build, deployment12 and ShellCheck/Actionlint passed; disposable Caddy/backend/PostgreSQL HTTP checks confirmed 24 rejected POST/PUT requests leave accounting unchanged, valid boundaries/remainder/zero-sum balances, and persistence after container recreation.

Release preparation (2026-10-15): issues #37/#38/#45 integrated through PRs #66/#67/#68 into dev. Synchronized already-released main history into dev to resolve the project-memory conflict and keep the release PR limited to the three P1 fixes. Issue branches target dev; tested releases use dev→main. No schema changes. Production deployment occurs only after the release PR is merged.

UI redesign planning (2026-10-16): isolated `feat/ui-redesign` from fetched `origin/dev` 597fc93. Audit/research at `docs/reviews/2026-10-15-ui-redesign/`, proposed spec/roadmap in `docs/superpowers/`, and four standalone editable concepts plus cursor options at `docs/concepts/ui-redesign/gallery.html`. Taste Skill redesign + UI UX Pro Max were inspected/applied. All artifacts are proposals; no application source, dependency, API, schema, commit, push or deployment change. Review direction/spec/plan before implementation; feature PR targets dev.

UI concept follow-up (2026-10-16): three palettes for each of four layouts (12 options, 24 light/dark combinations) in `docs/concepts/ui-redesign/colors.html`; working theme toggles/profile controls and Silk Ribbon, Comet Trail, Echo Rings directly in all four previews. Shared static palette/preview sources; URL/session-only appearance selection. Desktop/mobile, rendered contrast, dialog overlay and reduced-motion checks recorded alongside screenshots. Prototype artifacts only; no product implementation, commit, push or deployment.

Selected design (2026-10-16): user chose Pocket Objects with Seafoam & Petrol default, switchable Lilac/Plum and Peach/Cocoa plus independent Light/Dark, fixed Silk Ribbon mouse trail and short touch bloom/trace. Static preview now includes persisted Appearance controls, icon-and-label mobile/tablet dock with correct selection restoration, and bounded liquid-glass hover/press. Thirty responsive combinations and480 rendered-text checks passed; hover/reduction and touch rehearsal recorded, real-device touch/performance remains production verification. Spec/plan updated to the selected direction. No Angular/backend change, commit, push or deployment.

- [x] Greedy settlement wording (#57) — current UI/API/README copy describes netted transfers as suggestions and removes global fewest-payment claims; greedy algorithm and zero-sum, no-leftover, ≤N−1 guarantees are unchanged.

- [x] Pocket Objects Angular implementation (2026-10-17) — selected Seafoam/Petrol default with three independent palettes and System/Light/Dark modes across shell, overview, group/settings, auth, profile and all modals; local licensed fonts, SVG objects, icon dock, named focus-trapped dialogs, Silk Ribbon/touch and bounded liquid glass with accessibility fallbacks. Regression-first theme/motion/dialog/nav/cache/route ownership fixes; frontend 105/backend 138/deployment 12, Prettier and warning-free production build passed. Local synthetic browser flows cover registration/account switching, group/member edits, exact→percentage edit/delete, partial settlement, 320px/landscape and near-cap amounts. Physical-device touch/performance QA remains unmeasured. Implemented on feat/ui-redesign; user subsequently authorized commit/push, successful branch CI, then a PR to dev. User subsequently authorized resolving Codex review and merging PR #70 into dev after checks. No production deployment is authorized.

- [x] Dashboard deliberate retry (#46, 2026-10-17) — failed initial dashboard loads stop automatic `ensureLoaded()` retries until an explicit retry, while session clearing restores initial-load behavior. Regression covers consecutive 503s, stable loading/error state, no repeat request, and successful explicit retry. Frontend111, backend138, Prettier and production build pass; no backend changes.

- [x] Scoped expense availability (PR #70 review, 2026-10-17) — preserve Add/Edit Expense when a valid group scope exists even if the overview is pending or failed. Four reproduced failures now pass through rendered fields and correct POST/PUT cents payloads; the genuine no-group Create group prompt remains covered. Frontend 110/110, Prettier and production build pass. PR CI and review-thread resolution gate the user-authorized merge into dev; no production deployment.

- [x] Expanded expense refresh (#51, 2026-10-18) — current group reload already unsubscribes any in-flight receipt request, clears its cache, and collapses the expanded detail. Added rendered regression coverage for a detail request in flight during edit invalidation, updated summary display, and re-expansion fetching and rendering the saved amount, payer and shares. Frontend111, backend138, Prettier and production build passed; verification-only PR prepared for dev.

Release PR #71 preparation (2026-10-17): user authorized dev→main promotion, CI/Codex review resolution, merge and verification that Pocket Objects is live on split-easy.io. Synchronize the already-released #69 main history into dev while preserving the fully tested Pocket Objects application tree, session isolation and scoped-expense fix. Production delivery remains the existing main-only backend-first workflow; no database/schema change.

- [x] BCrypt registration byte boundary (#39, 2026-10-17) — reject passwords over 72 UTF-8 bytes in the shared registration service with a clear 400; preserve minimum 8 characters and unchanged passwords. Angular validates the same byte limit and explains multibyte characters. Red/green ASCII/accent/emoji API and form regressions; backend144/frontend116, Spotless/Prettier and production build passed. Local Chromium rejected 73/80/76-byte inputs with unchanged values and accessible errors; 72-byte registration/login covered against disposable PostgreSQL. No schema change or deployment.


- [x] Actionable concurrent expense conflicts (#41, 2026-10-17) — map Spring optimistic/stale-row failures to 409 with reload/review guidance. Regression-first concurrent authenticated PUTs against disposable PostgreSQL changed 200/500 to 200/409 while preserving exactly the winning expense, shares and zero-sum ledger; frontend keeps the edit open and never retries automatically. Backend141/frontend111, Spotless, Prettier and production build passed. Documented detected-transaction scope; no version/ETag/schema change, merge or deployment.


- [x] Null expense split validation (#40, 2026-10-17) — element-level `@NotNull` validation rejects null split entries before service work. MockMvc/Testcontainers regressions cover null-only and mixed valid/null entries for POST/PUT with UNEQUAL/PERCENTAGE and confirm expense rows, shares and balances remain unchanged. Backend142, frontend110, Spotless, Prettier and production build passed; PR targets dev, no schema change or deployment.


- [x] Strict integer JSON inputs (#43, 2026-10-17) — disable Jackson floating-point-to-integer coercion before expense POST/PUT, payment and split DTO validation. Raw-JSON regressions cover fractional cents/basis points, valid integers and rejected-edit participant row preservation; 9 reproduced failures → 13 focused passes. Backend151/frontend110, Spotless/Prettier, production build and deployment12 passed; authenticated MVC-to-PostgreSQL ledger checks passed. No schema/dependency changes, merge or deployment.


- [x] Raced unique conflicts (#42, 2026-10-17) — central post-rollback mapping recognizes only the email and group-membership unique constraints with SQLState `23505`; existing duplicate prechecks and unrelated integrity 500 classification remain. Deterministic concurrent MVC/PostgreSQL regressions exercise real prechecks/transactions and require one target row plus success/409 and mapping after transaction cleanup. Backend150/frontend110, Spotless/Prettier and production build passed; no live traffic or deployment.


- [x] Group financial read states (#49, 2026-10-17) — group detail waits for the combined group/balance/expense reads before showing financial conclusions; failures now identify balances/history as unavailable and offer a retry. Rendered HTTP regressions cover pending and failed balances and expense history, then successful retry. Frontend112, backend138, Prettier and production build pass; no schema change, merge or deployment.


- [x] Group route ownership (#48, 2026-10-17) — retain the redesign's route/account/destroy cancellation and combined required reads; clear prior pairwise balances and history immediately on group navigation. Five controlled regressions cover loaded-A reset, late A success/403 in either response order, rendered B header/balances/history/settings and expense/settlement modal scope, and destruction teardown. Regression-first failure confirmed; frontend115/backend138, Prettier/Spotless and production build passed. Local rendered Angular integration verified; no live browser/API check, merge or deployment.


- [x] Expense group ownership (#47, 2026-10-17) — member-load generations reject stale successes/errors (including A → B → A); switching groups clears member/payer/split state and save waits for members plus edit details. Edit group controls and component actions stay fixed to the original group. Ten component/HTTP regressions inspect B-first/A-last participants and the actual POST/PUT paths; original source reproduced nine failures plus three unhandled load errors. Backend138/frontend120, Spotless/Prettier and production build passed; independent review found no blockers. No live-browser or production-write claim; PR targets dev, no merge/deployment.


- [x] Dashboard refresh ownership (#50, 2026-10-18) — advance the existing cache generation on every refresh so only the newest current-session request can change data, errors or loading; explicit clear and account reset still invalidate outstanding callbacks. Eight overlap regressions cover both completion orders and old/new success/failure (seven failed before fix); focused33/frontend118/backend138, Prettier, warning-free production build and synthetic Chromium8/8 HTTP/rendering checks passed. Browser refreshes use the Angular debug API; no live backend, merge or deployment. Evidence: `docs/reviews/2026-10-17-p2/issue-50.md`.


- [x] Serialized split validation (#52, 2026-10-18) — new/edit expense totals, gate and POST/PUT reuse one cached nonnegative finite integer list; per-share nearest-hundredth rounding is explained in the form, zero omission and lossless exact edit prefill preserved. Regression first: 17 failures then focused28/full frontend133; backend138, Prettier, production build and local Chromium form/request checks pass. Browser APIs mocked; independent review cleared; no merge/deployment. Evidence: `docs/reviews/2026-10-17-p2/issue-52.md`.


- [x] Mobile account navigation (#54, 2026-10-18) — verified the Pocket Objects four-item labeled mobile/tablet dock already exposes Profile through 1024px. Real Chromium keyboard checks at 390, 820, and 821px with both persisted sidebar preferences, plus pointer checks at all three widths, confirmed Profile navigation, reachable sign-out, login redirection, and removal of synthetic access-token/user storage. No product change or duplicate component test was needed; evidence in `docs/reviews/2026-10-17-p2/issue-54.md`.


- [x] Partial group member additions (#55, 2026-10-18) — retain the created group and successful member additions, show per-email friendly results, and retry only failed emails against the same group. Sequential request/session cancellation remains intact. Regression-first component/HTTP coverage; frontend114/backend138, full Prettier and production build passed. Local mobile Chromium synthetic 200/404/409/503 flow verified failed-only retry, one create and group navigation; no production failure injection, merge or deployment.


- [x] Account-owned profile preferences (#53, 2026-10-18) — immutable user-ID storage and synchronous linked-signal resets isolate phone/currency/avatar/first-visit across login, registration, logout and reload; stale profile drafts cannot overwrite another account. Ownerless legacy preferences are discarded, while device appearance stays shared. Six privacy regressions reproduced before the fix; frontend117/backend138, Prettier and production build passed. Isolated Chromium synthetic A→B→A login/edit/logout and both-account reloads verified rendered isolation and persistence; auth/dashboard HTTP was intercepted. Client-only persistence remains intentional; no merge/deployment.


- [x] Concurrent partial group settings (#58, 2026-10-18) — Hibernate `@DynamicUpdate` prevents stale PATCH transactions from reverting omitted name/type/simplify-debts settings. Six deterministic authenticated owner/member PostgreSQL races failed before the fix, then passed in both commit orders; same-field/no-op behavior, sequential/null/omitted updates, validation and 403-before-404 remain covered. Backend 147/frontend 110, Spotless/Prettier and production build passed; independent review found no blockers. No schema change, merge or deployment.


- [x] Shared dialog focus continuity (#56, 2026-10-18) — recover heading focus through the existing CDK trap when settlement/content replacement removes its focused control, while preserving retained control focus; include the skip link in the inert background. Regression fails against the baseline shell, then frontend111/backend138, Prettier and production build pass. Synthetic Chromium at 1440×900, 320×568 and 844×390 covers all six dialog variants, keyboard trapping, select/Back and expense split changes, accessible names, Escape/Close launcher restoration and contained scrolling. Evidence: `docs/reviews/2026-10-17-p2/issue-56.md`; no real screen-reader/physical-device or production checks, merge or deployment.


- [x] Financial control accessibility (#59, 2026-10-18) — full display name plus email distinguishes duplicate-name payer/participant controls; exact/percentage labels include units. Preserves existing debt-switch checked and payer/category pressed semantics and financial calculations. Regression-first rendered tests, real Chromium AX/three-member payload checks, frontend 112/backend 138/deployment 12, Prettier and production build passed. No merge or deployment.

- [x] PR #90 formatting follow-up (2026-10-18) — reproduced CI Prettier failure from a final test-selector edit made after the earlier formatting pass; formatted that callback only. Exact full-source Prettier, frontend 112/112, production build and diff checks passed on the final content. No behavior change; backend and browser evidence from #59 remains applicable.



- [x] Idempotent payment operations (#61, 2026-10-18) — optional UUID operation IDs scoped to authenticated requester/group, V9 unique identity, native PostgreSQL conflict arbitration and original-outcome replay; valid changed payloads return 409, separate IDs/legacy omitted IDs remain additive. Angular retains the pending identity through uncertain errors and modal reopen, resets on success/different submitted intent/account change, and cancels old-session requests. Corrected regression-first real API races, 147 backend/114 frontend tests, Spotless/Prettier/production build and production-build Chromium retry/reopen rehearsal passed; independent review found no blockers. Contract/limits and evidence in `docs/api/payment-retries.md` and `docs/reviews/2026-10-18-p2/issue-61.md`. In-memory client identity is lost on reload/cross-tab; no merge/deployment.


- [x] Authentication abuse limits (#62, 2026-10-18) — login 30/IP/5min plus 5/(normalized account, IP)/5min; registration 20/IP/hour configurable. Atomic bounded process-local windows, generic 429 with CORS-exposed Retry-After, friendly form retry notices and native private Caddy/Tomcat visitor-IP forwarding. Regression-first backend 146/frontend 112, formatting/build, deployment 12 and pinned Caddy validation passed; real trusted/untrusted proxy-spoof, six-member shared-IP signup, direct-dev HTTP and Chromium actual-429 form checks passed. No schema/dependency/signing/session change; restart resets and multiple instances require shared enforcement. External Cloudflare rules remain unknown; no merge/deployment.


- [x] #63 session lifecycle contract (2026-10-18): documented intended expiry→re-login, client-only logout limits, and requirements before refresh/revocation. Added real-filter expired/issuer/signature/algorithm rejection coverage with valid control; backend 143/143, frontend 110/110, Spotless/Prettier and production build pass. No authentication behavior/schema change.

P2 integration and release preparation (2026-10-18): user authorized merging PRs #72–#93 into dev, combined final checks, then a release PR to main. All 22 exact heads retained; released main history synchronized with no tree change. Shared exception/auth/test conflicts preserve both fixes. Combined backend207/frontend187/deployment12, formatting, production build, ShellCheck/Actionlint and synthetic Chromium dashboard/split checks pass; independent integration review found no blockers. Evidence: `docs/reviews/2026-10-18-p2/integration.md`. Dev CI gates release PR creation; main merge and production deployment remain unauthorized.
