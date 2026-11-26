# UI10 — comments + attachments, search, "Download my data" (frontend only)

## Result
- `comments/` and `attachments/`: panels behind a "Comments and files" toggle on the group receipt and each personal expense (lazy, so no extra reads until opened). Own-only delete, archived groups read-only, 2000-char limit, client pre-check (JPEG/PNG/WebP/PDF, 5 MB) with server 400/413/409 messages.
- Trust boundary: uploads are only ever downloaded (blob + object URL revoked right away via `shared/save-blob.ts`); no `<img>`/`<iframe>`/`<object>`; the spec asserts none render.
- `search/` (`/search`): text, group, dates, currency; amount bounds disabled until a currency is chosen; keyset Load more; empty/failed/retry. Personal rows link to `/friends` (the API gives no friend id).
- `backup/`: Profile "Download my data" saves `spliteasy-export-<date>.zip`; pending + failure states.
- Shell header: Search and Analytics icon links (`search.analytics` key used because PR #140 had not landed; `/analytics` route is P04's).
- Translation keys: `comments.`, `attachments.`, `backup.`, `search.`, plus `personalExpenses.commentsAndFiles`; all four catalogues, hi/es/fr machine-drafted (Beta).

## Red to green
- Specs written first; first run failed to compile (modules missing). After implementation: 16 new specs pass.
- First full run: 40+ group-detail specs failed with "Expected no open requests" because the panels fetched on every expansion; fixed by making the panels lazy behind a toggle.
- Search spec failed only in combined runs: a field initializer `currencies = CURRENCIES` read `undefined`; a getter fixed it (cause not root-caused).

## Gate (Node 22.17.1)
- `ng test --watch=false`: 74 files / 475 tests pass. Prettier check src clean. Production build OK (initial 625.49 kB; the existing >500 kB budget warning).
- Not verified: real browser (390px header width, real download), backend (unchanged).

## Agents.md delta
- Add to Frontend Conventions: "Comments/attachments (H02-B, P01-B, P07-B): panels are lazy behind a toggle; uploads are download-only blobs with revoked object URLs; `shared/save-blob.ts` is the one save helper. Search amount bounds need a currency. Personal search rows link to /friends."
- Progress Log: one line for this package.
