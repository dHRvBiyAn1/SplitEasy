# Alignment Wave 9 — package plans (12 November 2026)

Integrator-written plans for C02, H02-A and P07-A + P01-A. Rules, anchors and migration numbers
follow the [parallel batch plan](2026-11-11-alignment-parallel-batches.md); this file settles the
design so workers implement rather than decide. Base: `dev` after Wave 8 (#123–#126). A worker
that finds a question this plan does not answer stops and reports instead of guessing.

Owner decisions (12 November 2026): ship **Hindi, Spanish and French** alongside English, designed
so more languages can be added later without code changes beyond one catalogue file and one list
entry; store attachments **in PostgreSQL with a size cap**.

## Ownership this wave

| Package | Owns | Must not touch |
| --- | --- | --- |
| **C02** languages (V29) | Every frontend `*.html` and component string, new `core/i18n/*`, `public/i18n/*.json`, locale-aware formatting helpers (`app-money`, dates), profile language control, `users.locale` in `User`/`ProfileService`/`dto` for `/api/me` | All other backend |
| **H02-A** comments + attachments backend (V30) | New `comment/*` and `attachment/*` backend packages, `ActivityRecorder` (add comment event only) | All frontend; existing services (call only) |
| **P07-A + P01-A** account export + search backend (V31 only if indexes are needed) | New `export/*` and `search/*` backend packages (read-only; may call H05's `HistoryQuery`/`HistoryCsvWriter`) | All frontend; existing services (call only) |

Anchors `c02`, `h02`, `p07-p01` are in `openapi.yaml` (paths and schemas) and `app.routes.ts`.

## C02 — Hindi, Spanish and French (V29)

Runtime translation with no new dependency, so the server-owned language preference switches the UI
without a rebuild or a per-locale deployment:

- `core/i18n/`: `I18nService` (signal `locale`, `SUPPORTED_LOCALES = ['en','hi','es','fr']` as the
  single list to extend), a pure `t` pipe (`{{ 'expense.add' | t }}`, interpolation
  `{{ 'balance.owesYou' | t: { name, amount } }}`) and plural keys chosen by `Intl.PluralRules`
  (`key_one` / `key_other`, plus whatever categories the locale needs).
- Catalogues: `public/i18n/en.json` is the source of truth; `hi.json`, `es.json`, `fr.json` are
  loaded lazily over HTTP (same origin, cached). A missing key falls back to English, never to the
  raw key. English is bundled so first paint never waits.
- A unit test fails if any locale lacks a key present in `en.json` or has an unknown key, and if any
  interpolation placeholder differs from English. Adding a language = add `xx.json`, add `'xx'` to
  `SUPPORTED_LOCALES`, add `'xx'` to the backend allow-list; the test enforces completeness.
- Formatting: money (`app-money`), dates and numbers use `Intl` with the active locale (Hindi gets
  Indian digit grouping automatically via `hi-IN`). Currency codes, minor-unit exponents and amounts
  never change — only presentation. `lang` on `<html>` follows the active locale.
- Preference: V29 `users.locale varchar(8) not null default 'en'`, validated against a backend
  allow-list `en, hi, es, fr`; returned and patched through the existing `GET/PATCH /api/me`.
  Signed-out pages use the browser language if supported, else English; after sign-in the server
  preference wins. Profile gets a Language select. Switching is instant (no reload).
- Translations: the worker writes all three catalogues; mark them machine-drafted in the evidence
  file — native review is a follow-up before R2 is announced. Keep keys semantic
  (`groups.settings.leave.confirm`), never English sentences.
- Backend error messages stay English this wave (known gap; record it). Never translate data:
  group names, descriptions, notes and people's names are shown as entered.
- Accessibility: translated `aria-label`s and live-region text; no truncation regressions at 320px
  for the longest strings (French/Spanish run ~30% longer).
- Tests: rendered specs switch locale and assert translated text, Hindi digit grouping in
  `app-money`, fallback for a missing key, profile language save → instant switch, and session
  reset returns to the browser/English default. Existing specs keep passing in English.

## H02-A — comments and attachments backend (V30)

- V30: `expense_comments(id, expense_id→expenses cascade, author_id→users, body varchar(2000),
  created_at, deleted_at null)` and `attachments(id, expense_id→expenses cascade, uploaded_by→users,
  file_name varchar(255), content_type, size_bytes, sha256, content bytea, created_at,
  deleted_at null)`; indexes per conventions.
- Limits: 5 MB per file, at most 10 live attachments per expense; allowed types `image/jpeg`,
  `image/png`, `image/webp`, `application/pdf`, verified by **magic bytes**, not the client header.
  Reject anything else with 400/413. File names are sanitized for display and never used as paths.
- Routes under `/api/groups/{groupId}/expenses/{expenseId}/comments` and `.../attachments`
  (bind `{groupId}` so the personal-ledger guard applies; member-only, 403 before 404, archived
  groups read-only → 409 on writes): list, create, delete own (soft). Attachment download streams the
  bytes with the stored content type, `Content-Disposition: attachment`, `X-Content-Type-Options:
  nosniff` and `Cache-Control: private, no-store`; metadata lists never include bytes.
- Personal expenses: mirror the same routes under `/api/friends/{friendId}/expenses/{expenseId}/…`
  through `PersonalLedgerService` authorization (call it; do not edit it).
- Comments emit an H03 event (`COMMENT_CREATED`) through a new `ActivityRecorder` method, gated by
  the `expenses` preference.
- Account closure: comments/attachments by a closed account stay; the author shows "Closed account".
- Tests: type spoofing (PNG header claimed on non-PNG bytes) rejected, oversize 413, non-member 403,
  archived 409, someone else's delete 403, download headers, personal-ledger routes, event once.

## P07-A + P01-A — account export and search backend

- **Export (P07-A):** `GET /api/me/export` streams a ZIP for the signed-in account: `profile.json`,
  `groups.json` (groups currently accessible), and one CSV per group plus the personal ledgers,
  reusing H05's CSV writer/query (authorized scopes only, deleted entries excluded). No other users'
  contact data beyond what the app already shows. Streamed, never fully buffered.
- **Search (P01-A):** `GET /api/search?q=&groupId=&friendId=&from=&to=&currency=&minAmount=&maxAmount=&cursor=&limit=`
  over the viewer's accessible expenses, payments and refunds: description, note, category, person
  name, date range, and amount bounds **only together with a currency** (never compare unlike
  currencies). PostgreSQL `ILIKE` with escaped wildcards first; add a trigram index in V31 only if a
  test shows it is needed. Keyset paging like H05 (limit 50/max 100). Soft-deleted rows excluded;
  removed membership removes results; personal-ledger `groupId` 404 like history.
- Tests: authorization (non-member, former member), wildcard escaping (`%`, `_`), currency-required
  amount filters, paging stability, export contains only authorized scopes and streams.

## Rollout

Drain old backends before V29–V31. Rollback keeps the schema; forward fix only. R2 (release of
M2+M3, migrations V19 onward) follows this wave and needs native review of the translations,
a populated-database migration rehearsal and a browser pass first.
