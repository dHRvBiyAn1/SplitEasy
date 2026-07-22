<div align="center">

# 🍋 SplitEasy

### Split the bill. *Keep the friends.*

A full-stack, Splitwise-style expense-splitting app — track who paid what across
houses, trips and dinner clubs, split it **equally, by exact amounts or by
percentages**, then settle up in one tap.

![Java](https://img.shields.io/badge/Java-21-b07219?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![Angular](https://img.shields.io/badge/Angular-21-DD0031?logo=angular&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?logo=postgresql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200?logo=flyway&logoColor=white)
![Tests](https://img.shields.io/badge/tests-97%20backend%20%C2%B7%2032%20frontend-brightgreen)

*Money is integer cents. Balances are derived, never stored. Every split sums to the total — always.*

</div>

---

## ✨ What it does

| | Feature | The gist |
|---|---|---|
| 👥 | **Groups** | Create a group (home / trip / dining / event), add members by email — everyone's an equal member, no admin ceremony. |
| 🧾 | **Expenses** | Log who paid, pick a category and date, choose the participants. Edit is a clean full-replace; delete is instant. |
| ➗ | **Three split modes** | **Equal** (remainder cents absorbed by the payer), **Unequal** (exact cents, must sum to the total), **Percentage** (basis points, must sum to 100%). |
| ⚖️ | **Live balances** | Net per member, plus a pairwise *"who owes whom, to me"* view — recomputed from source rows on every read, so they can never drift. |
| 🤝 | **Settle up** | Record a payment between two members. Overpayment allowed — real people round up. |
| 🧠 | **Simplify debts** | A greedy max-creditor/max-debtor matcher suggests the minimal transfer set (≤ N−1 transfers, no leftover cents). One tap turns a suggestion into a payment. |
| 📊 | **Dashboard** | One aggregated payload: cross-group totals, per-group cards, a People list, and a merged activity feed with your +lent / −borrowed delta per item. |
| 👤 | **Profile & theming** | Inline-editable profile, avatar colors, currency choice, and a System / Light / Dark theme toggle that overrides the OS. |

## 🎨 The "Evenly" design system

The UI is an editorial, token-driven design — not a component-library skin:

- **Instrument Serif** display type over **Hanken Grotesk** UI text
- Lime accent `#B6F531` on near-black, green-tinted off-white background
- **Sharp corners everywhere** (`border-radius: 0`) — deliberate, editorial, calm
- Semantic money colors: green = you're owed, pink = you owe — one CSS convention (`.dc-amount`) app-wide
- Dark mode follows the OS, with a user override that wins via `data-theme`
- Every value lives in [`_tokens.scss`](frontend/src/app/styles/_tokens.scss); components never hardcode a hex

## 🏗 Architecture

```
 Angular 21 (4200)                Spring Boot 4.1 (8080)               PostgreSQL 16 (5433)
┌────────────────────┐  /api/*  ┌──────────────────────────┐  JPA    ┌─────────────────────┐
│ standalone + signals│ ───────▶ │ controller → service →   │ ──────▶ │ users · groups      │
│ authInterceptor JWT │  proxy   │ repository → entity      │ Flyway  │ memberships         │
│ ModalService shell  │ ◀─────── │ DTO records at boundary  │ V1…V7   │ expenses · shares   │
│ token design system │   JSON   │ Log4j2 leveled logging   │         │ payments            │
└────────────────────┘          └──────────────────────────┘         └─────────────────────┘
```

**The one rule that shapes everything:** balances are *derived*. A member's net is

```
net = Σ paid  −  Σ share owed  +  Σ payments made  −  Σ payments received
```

computed by a fixed number of aggregate `GROUP BY` queries (never a per-expense
loop). Because every expense's shares sum exactly to its amount and every payment
contributes +X/−X, **a group's balances always sum to zero** — enforced as a test
invariant.

### Split engine — Strategy pattern

```java
// No switch statements. New split type = new @Component, nothing else changes.
Map<SplitType, SplitStrategy> strategies;   // EQUAL | UNEQUAL | PERCENTAGE
List<Share> shares = strategies.get(type).split(ctx);
```

Each strategy owns its validation and math; rounding is deterministic — floor
every share, the **payer absorbs the leftover cents**. All three types persist
into the same `share_cents` column, so balance math is split-type-agnostic.

### Security posture

- JWT (HS256) via Spring's OAuth2 resource server; BCrypt passwords; DTOs only — entities are never serialized
- **403-before-404**: membership is checked *before* existence, so the API never reveals whether a group exists to a non-member
- No default JWT secret — non-dev boots **fail fast** without `APP_JWT_SECRET` (≥ 32 chars)
- Amounts bounded (≤ $10B) against griefing and aggregate overflow

## 🚀 Getting started

> Prereqs: **Java 21**, **Node 22+**, **Docker Desktop** (dev DB *and* backend tests via Testcontainers).

```bash
# 1 · Database — Postgres 16 on host port 5433 (loopback only)
docker compose up -d

# 2 · Backend — Flyway migrates on startup, API on :8080
cd backend && ./mvnw spring-boot:run

# 3 · Frontend — dev server on :4200, proxies /api/* to the backend
cd frontend && npm install && npm start
```

Open **http://localhost:4200**, register, and start splitting.

```bash
# health check
curl localhost:8080/api/health   # → {"status":"UP","service":"spliteasy"}

# 60-second API tour
TOKEN=$(curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"ada@ex.com","password":"password123","displayName":"Ada"}' | jq -r .accessToken)
curl -s -X POST localhost:8080/api/groups -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"Lisbon Trip","type":"TRIP"}'
```

### Tests

```bash
cd backend  && ./mvnw test                # 97 tests — Testcontainers spins a throwaway Postgres
cd frontend && ng test --watch=false      # 32 specs — Vitest, headless
```

## 🔌 API at a glance

All under `/api`; everything except `auth/*` and `health` needs `Authorization: Bearer <jwt>`.
Group routes are member-only — non-members get **403, even for nonexistent groups**.

| Method | Endpoint | What it does |
|---|---|---|
| `POST` | `/auth/register` · `/auth/login` | issue a 1-hour access token |
| `GET` | `/dashboard` | totals, group cards, people, settlements, activity feed |
| `GET/POST` | `/groups` | list mine / create |
| `GET` | `/groups/{g}` | detail with members |
| `POST` | `/groups/{g}/members` | add a member by email |
| `GET/POST` | `/groups/{g}/expenses` | list / create |
| `GET/PUT/DELETE` | `/groups/{g}/expenses/{e}` | detail / full-replace edit / delete |
| `GET` | `/groups/{g}/balances` · `…/balances/mine` | net per member / pairwise to me |
| `GET/POST` | `/groups/{g}/payments` | settle-up history / record |
| `GET` | `/groups/{g}/debt-simplification` | minimal transfer suggestions |

## 🗄 Data model

```
users ──┐                      ┌── expenses ──── expense_participants
        ├── group_memberships ─┤       (amount_cents, split_type,        (share_cents —
groups ─┘   (unique per pair)  │        category, spent_on)               sums to amount)
                               └── payments
                                       (payer → payee, amount_cents)
```

Seven Flyway migrations (`V1…V7`), UUID keys throughout, `BIGINT` cents, and
composite indexes tuned to the real queries —
`(group_id, spent_on DESC, created_at DESC)` on expenses,
`(group_id, created_at DESC)` on payments — so the group list, history and
activity feed are each **one index scan**.

## 📁 Project layout

```
SplitEasy/
├── docker-compose.yml            # Postgres 16 (host :5433)
├── Agents.md                     # living project memory / conventions
├── design/evenly.dc.html         # the design reference the UI is built from
├── backend/                      # Spring Boot 4.1 · Java 21 · Maven
│   └── src/main/java/com/spliteasy/
│       ├── controller/           # REST endpoints (thin)
│       ├── service/              #   business logic · split/ strategy engine
│       ├── repository/           #     Spring Data JPA + aggregate queries
│       ├── entity/               #       JPA entities (Lombok, UUID PKs)
│       ├── dto/                  # request/response records, grouped by resource
│       ├── exception/            # GlobalExceptionHandler → WARN 4xx / ERROR 5xx
│       ├── config/               # security, CORS, @CurrentUserId resolver
│       └── util/                 # Emails.normalize, shared helpers
└── frontend/                     # Angular 21 · standalone components · signals
    └── src/app/
        ├── dashboard/  groups/  expenses/  balances/  payments/  debts/
        ├── modals/               # global modal system (one shell, many bodies)
        ├── profile/              # profile page + theme choice
        ├── core/                 # api client, auth, guards, interceptor
        └── styles/               # _tokens.scss + _ui.scss — the design system
```

## 🧭 Engineering principles

1. **One source of truth.** Balances derive from rows; the three balance views (net, pairwise, simplified) all read the same persisted data.
2. **Integer cents, end to end.** No floats near money — `Math.round` at the input edge, `BIGINT` in the DB.
3. **Reuse over re-derivation.** `MembershipGuard`, `@CurrentUserId`, `Emails.normalize`, `.dc-amount`, `SplitContext` factories — one way to do each thing.
4. **Boring queries, fast pages.** Aggregate `GROUP BY`s, `join fetch`, correlated-subquery counts — no N+1s, verified in tests.
5. **Fail loudly, log properly.** Log4j2 with real levels: DEBUG flow (dev), INFO business events, WARN 4xx, ERROR unexpected 5xx with stack traces.

## 🗺 Roadmap

- [ ] Refresh tokens (currently 1-hour access tokens only)
- [ ] Server-side profile persistence (phone / currency / avatar live client-side today)
- [ ] Group roles (schema is ready via `group_memberships`)
- [ ] Recurring expenses & receipts

---

<div align="center">

**Built with** ☕ **Java 21 · 🅰️ Angular 21 · 🐘 PostgreSQL — and an unreasonable attention to rounding cents.**

*Even splits. Even friendships.*

</div>
