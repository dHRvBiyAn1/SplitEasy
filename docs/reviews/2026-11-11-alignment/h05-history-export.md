# H05 bounded history and CSV export — evidence

Branch `feat/alignment-h05-history-export`. Contract: [history](../../api/history.md).

## Reviewed from the previous worker
Kept: `HistoryController/Service/CsvWriter`, JDBC `HistoryQuery`, DTOs, 7 tests.
Fixed:
- CSV content type was set before authorization, so a 403/404 became a servlet error; headers are now set lazily after authorization.
- `updatedAt` could be null (NPE) for entries without revisions; falls back to `created_at`.
- A payer outside the split had no CSV row, so paid totals did not reconcile; a payer row is now emitted. Test added.
- Removed V22: the keyset runs over a UNION CTE, so the refund index would not serve ordering; V22 is released unused.

## Review follow-ups
- Codex P2: CSV text guard now looks past leading spaces/tabs (unit regression, red on old code).
- Personal ledgers (G02.2): a `groupId` that is the viewer's personal ledger is 404 (403 first for non-members); personal entries stay in account-wide/friend history with a null `groupName` / empty CSV `group_name`.

## Frontend
`history/*` (models, `HistoryService`, `HistoryComponent`), route `groups/:id/history`, link on group detail. The CSV read uses `HttpClient` with a blob response because `ApiService` is JSON-only (not in this row).

## Testing
- Backend `HistoryIntegrationTest` 9 + `HistoryCsvWriterTest` 2; full `spotless:check verify` 395 passing (after merging dev with G02.2).
- Frontend Node 22: 399 tests / 57 files passing (5 new history specs), Prettier clean, production build ok (initial bundle 555.08 kB, existing 500 kB budget warning).
- OpenAPI parses; two paths and two schemas added under the H05 anchors.
- Not verified: native browser run, very large (>100k rows) export timing, friend-scope plan performance.

## Agents.md delta
- Progress Log: `- [x] Alignment H05 bounded history/export (2026-11-11) — GET /api/groups/history (keyset (financialDate DESC, id DESC), limit 50/max 100, from/to and updated bounds, group/friend scope, member check first) and streamed CSV export with per-participant paid/owed/settlement/net minor units and formula-safe text; no migration. Angular groups/:id/history with paging, filters, retry, download. Contract: docs/api/history.md.`
- API table: add `GET /groups/history`, `GET /groups/history/export`.
- Migration V22 is unused/released.
