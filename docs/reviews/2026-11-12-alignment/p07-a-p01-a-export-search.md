# Wave 9 — P07-A account export + P01-A search (backend only)

Branch `feat/alignment-p07-p01-export-search`, PR to `dev`. No migration: V31 stays unused because no
test showed an index was needed (search is `ILIKE` over the caller's already scope-limited rows).

## What shipped

- `GET /api/me/export` — streamed ZIP: `profile.json` (own account row, never the password hash),
  `groups.json`, `personal-ledgers.json`, `groups/{id}.csv` per currently accessible group and
  `personal/{id}.csv` per personal ledger. CSVs are H05's `HistoryQuery.export` + `HistoryCsvWriter`
  (deleted entries excluded). Scopes come from the caller's own memberships. One read-only
  REPEATABLE_READ snapshot; each entry is written and flushed in turn, never buffered whole.
- `GET /api/search` — over expenses, settlements and refunds: description, note, category and the
  names of the people taking part. `q` is matched literally (`\`, `%`, `_` escaped, `ESCAPE '\'`).
  Optional `groupId`, `friendId`, `from`, `to`, `currency`, `minAmount`/`maxAmount` (minor units,
  **400 without `currency`**), keyset cursor identical to H05 (limit 50/max 100). Returns H05's
  `HistoryPage`. Same authorization as history: non-member group 403, personal-ledger `groupId` 404,
  unknown friend 404; deleted rows and groups the caller left never appear.

## Deviation to note

`HistoryCsvWriter` is package-private in `com.spliteasy.service`, so the `export` package could not
call it. Without editing H05 files I added one small new class, `service/HistoryCsvExport` (a public
`@Component` that writes header + rows through the existing writer/query, no authorization of its
own). It is outside the `export/*`/`search/*` ownership row but is a new file, not an edit.

## Red → green

`SearchAndExportIntegrationTest` (8 HTTP cases) written first: 7 failed (no endpoints; the 401 check
passed trivially). After the implementation, three failures were test-fixture bugs (shared-DB
`LIMIT 1` ledger lookup, unsettled member leave, `FOOD` category) and one real bug: Jackson's
`writeValue(Writer)` closed the writer and with it the ZIP (`Stream closed`); fixed by writing JSON
as a string. Final: 8/8.

## Verification

See the PR Testing section for exact backend/frontend numbers.

## Agents.md delta

Progress Log line:

- [x] Alignment P07-A + P01-A export and search backend (2026-11-13) — `GET /api/me/export` streams a
  ZIP (profile, groups, personal ledgers, one H05 CSV per authorized scope, deleted rows excluded);
  `GET /api/search` finds expenses/payments/refunds by description, note, category and person with
  literal (escaped) matching, date range, and amount bounds only together with a currency, using H05's
  keyset paging and scope authorization. No migration (V31 unused). Backend only.

API table rows to add: `GET /me/export` (own data ZIP); `GET /search` (keyset search, amount bounds
need `currency`). Convention: `service/HistoryCsvExport` is the public door to the package-private H05
CSV writer; callers must pre-authorize scopes. Jackson `writeValue(Writer)` closes the writer — write
strings when streaming several entries into one ZIP.
