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
`{"status":"UP","service":"spliteasy-backend",...}`. The frontend toolbar
shows "● UP" when the full chain works.

Auth/JWT config: the backend signs HS256 tokens with `app.jwt.secret`
(dev default in `application.properties`). **Override `APP_JWT_SECRET`
(>= 32 chars) in any non-dev environment.** Token lifetime is
`app.jwt.expiration-seconds` (default 3600).

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
  `SecurityConfig`). Controllers read the caller via `@AuthenticationPrincipal Jwt`.
  Passwords hashed with BCrypt; entities are NEVER serialized — DTOs only.
- **Group authorization is flat**: any member can view a group and add members
  (by email). Room for roles later via `GroupMembership` (explicit join entity).
- **Avoid N+1 on collections**: fetch members with `join fetch m.user`; compute
  group member counts with one aggregate query (see `GroupMembershipRepository`).
- Frontend: Angular Material (v21, CSS-based animations — `@angular/animations`
  is NOT a dependency, so don't add `provideAnimations*`). Auth token is attached
  and 401s handled by `authInterceptor`; authenticated routes use `authGuard`.
  Vitest is the test runner — use `vi.spyOn(...).mockReturnValue(...)`, not
  Jasmine's `spyOn(...).and.returnValue(...)`.

## Data Model (evolving)

Migration `V2__users_and_groups.sql`. All PKs are `uuid`.

- **users** — `id`, `email` (unique, not null), `password_hash` (not null),
  `display_name` (not null), `created_at`.
- **groups** — `id`, `name` (not null), `created_by` → `users.id` (not null),
  `created_at`.
- **group_memberships** — `id`, `group_id` → `groups.id` (ON DELETE CASCADE),
  `user_id` → `users.id`, `joined_at`. Unique `(group_id, user_id)`.
  Indexed on both `user_id` and `group_id`.

Relationships: User ↔ Group is many-to-many **through group_memberships**;
Group → User (created_by) is many-to-one. Creating a group auto-inserts a
membership for the creator (so the creator is a member, not a special case).

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
