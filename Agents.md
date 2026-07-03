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
- **Database**: PostgreSQL (via Spring Data JPA + Flyway/Liquibase for migrations)
- **Frontend**: Angular 18+, standalone components, Angular Material (or TBD)
- **Auth**: TBD — decide during planning (likely Spring Security + JWT)
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
`{"status":"UP","service":"spliteasy-backend",...}`. The frontend root page
shows "Backend: UP" when the full chain works.

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
- Backend CORS allows `http://localhost:4200` (`WebConfig`), though dev traffic
  normally uses the ng-serve proxy instead.

## Data Model (evolving)

*(To be filled in once the initial plan is finalized — entities, relationships,
key constraints.)*

## Domain Rules to Remember

- All monetary amounts stored as integer cents (or BigDecimal with fixed
  scale) — never floating point.
- Every expense split must sum exactly to the total expense amount (handle
  rounding remainders deterministically, e.g., first payer absorbs the cent).

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
