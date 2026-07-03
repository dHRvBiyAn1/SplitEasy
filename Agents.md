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

*(To be filled in once the skeleton is scaffolded — exact commands, ports, env vars, docker-compose setup.)*

- Backend: `./mvnw spring-boot:run` (expected port 8080)
- Frontend: `ng serve` (expected port 4200)
- DB: `docker-compose up -d postgres` (TBD — compose file not yet created)

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

*(Empty for now. Agents: when you make a design decision or the human corrects
you, add a line here so it's not relitigated next time.)*

-

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

- [ ] Project skeleton (backend + frontend scaffolding) — not started
