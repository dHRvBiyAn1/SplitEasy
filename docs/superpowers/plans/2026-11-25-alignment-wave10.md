# Alignment Wave 10 — package plans (25 November 2026)

Integrator-written plans for H07-A, the H02-B + P01-B + P07-B feature UIs and P04 + P05. Rules,
anchors and migration numbers follow the [parallel batch plan](2026-11-11-alignment-parallel-batches.md);
this file settles the design so workers implement rather than decide. Base: `dev` after R2
(`v0.3.0`, PR #137). A worker that finds a question this plan does not answer stops and reports.

Owner decision (25 November 2026): offline support uses **`@angular/service-worker`** (Angular's
official package, pinned to the same version as the rest of Angular). It is the only new dependency
this wave.

## Ownership this wave

| Package | Owns | Must not touch |
| --- | --- | --- |
| **H07-A** installable PWA (subsystem plan + slice 1) | `package.json`/`package-lock.json` (only the one new dependency), `angular.json` service-worker settings, `ngsw-config.json`, `src/app/app.config.ts`, `src/index.html` (manifest link, theme colour), `public/manifest.webmanifest` and icons, new `core/pwa/*`, the H07 subsystem plan doc | `core/api/*`, `core/auth/*`, `PendingWritesService`, app shell templates, any data caching |
| **UI** H02-B comments + attachments, P01-B search, P07-B backup | New `comments/`, `attachments/`, `search/`, `backup/` folders; expense receipt/detail rendering in `groups/group-detail.*` and in the friend page's personal-expense detail (`friends/personal-expenses.*`); app shell header (`app.html`/`app.ts`/`app.scss`) for a search entry; the data-download section of `profile/*` | Backend; `modals/new-expense-modal.*`; `app.config.ts` |
| **P04 + P05** analytics, saved split rules (V32) | New backend `analytics/*` and `splitrule/*` packages; frontend new `analytics/` and `split-rules/` folders; `modals/new-expense-modal.*` (apply/save rule controls only) | Existing services (call only), group detail, friends UI, shell |

Translation keys: each package adds keys only under its own prefixes and keeps each catalogue
sorted — H07: `pwa.`; UI: `comments.`, `attachments.`, `search.`, `backup.`; P04+P05: `analytics.`,
`splitRules.`. All four catalogues get every key (machine-drafted hi/es/fr, same as C02).

Anchors `h07`, `ui10`, `p04-p05` are in `openapi.yaml` (paths and schemas) and `app.routes.ts`.

## H07-A — installable PWA, app shell only

First write `docs/superpowers/plans/2026-11-25-alignment-h07.md`: the subsystem plan for all four
roadmap slices (1 installability/app shell; 2 account-scoped read cache with logout purge;
3 IndexedDB operation queue for expense writes carrying operation ID + expected version; 4 conflict
and reconnect UI), with the account-isolation, token-expiry and "pending money looks different"
rules from the roadmap. Then implement **slice 1 only**:

- `ng add`-equivalent setup of `@angular/service-worker` at the repo's Angular version:
  `provideServiceWorker('ngsw-worker.js', { enabled: !isDevMode(), registrationStrategy:
  'registerWhenStable:30000' })` in `app.config.ts`, `serviceWorker` in the production build config.
- `ngsw-config.json`: `app` asset group (prefetch: index, JS, CSS, fonts) and a lazy group for
  `/i18n/*.json` and images. **No `dataGroups`**: API responses are never cached in this slice (they
  are cross-origin to `api.split-easy.io` anyway). `navigationUrls` excludes `/api`.
- `public/manifest.webmanifest` (name, short name, start URL `/dashboard`, `display: standalone`,
  theme/background colours from the Seafoam palette tokens, 192/512 and maskable icons generated from
  the existing favicon/brand mark) and the `index.html` link plus `theme-color`.
- `core/pwa/pwa-update.service.ts`, started from an app initializer: on `VERSION_READY`, activate and
  reload on the **next router navigation** (never mid-form); on `unrecoverable` state, reload once.
  No UI, so no shell edit.
- Offline behaviour this slice: a reload with no network shows the cached app shell; data requests
  fail with the existing "unavailable — retry" states. Do not claim offline data.
- Tests: unit spec for the update service (version ready → reload only at next navigation;
  unrecoverable → reload) with `SwUpdate` stubbed; production build produces `ngsw.json` and
  `ngsw-worker.js`; a real browser check against `ng build` output served locally (own
  `--user-data-dir`): manifest valid, service worker active, offline reload renders the shell,
  `/api` never served from the worker cache. Record it in the evidence file.
- Deployment note for the evidence: Cloudflare Pages must serve `ngsw.json` and
  `ngsw-worker.js` uncached; check the current headers/`_headers` setup and report (do not change
  deployment files without saying so).

## UI — comments, attachments, search, backup (frontend only)

Backends are live (H02-A, P01-A, P07-A; contracts in `openapi.yaml` and `Agents.md`).

- **Expense detail panel** (group receipt in `groups/group-detail.*` and the friend page's
  personal-expense detail): a **Comments** list (author — "Closed account" when closed — time, body;
  add with a 2000-char limit; delete own) and an **Attachments** list (file name, type, size, uploader;
  download; delete own; upload with client-side pre-checks of type JPEG/PNG/WebP/PDF and ≤5 MB, and
  the server's 400/413 shown as actionable messages). Archived groups: read-only, controls disabled
  with the existing archived explanation. Downloads use `HttpClient` blob + an object URL revoked
  after use; never render attachments inline (no `<img src>` of user uploads, no iframe).
- **Search** (`search` route below the `ui10` anchor, entry from the shell header): text box plus
  optional group, date range, and amount min/max that become enabled only after a currency is chosen
  (the API requires it). Results list with type, date, description, group (or "Personal"), amount via
  `app-money`, linking to the group or friend page; keyset "Load more"; empty/failed/retry states.
- **Backup** (Profile → "Download my data"): calls `GET /api/me/export` as a blob and saves
  `spliteasy-export-<date>.zip`; pending state, failure message, and a one-line description of what
  the ZIP contains.
- All HTTP through existing resource-service patterns (one service per resource), session
  cancellation (`takeUntil(session.changes)`), tokens and `.dc-*` only, translation keys under the
  prefixes above.
- Tests (rendered Vitest, red first): comment add/delete-own/others-read-only; attachment pre-check
  rejects 6 MB and `.exe`, server 413 message, download triggers a blob save, archived disables
  controls; search currency gating, paging, empty and error states; backup success and failure.

## P04 + P05 — analytics and saved split rules (V32)

**Analytics (P04)**, read-only:

- `GET /api/analytics?from=&to=&groupId=&groupBy=category|month|person` → per-currency rows
  `{currency, key, label, groupTotalMinor, myShareMinor, myPaidMinor}` over the viewer's accessible,
  non-deleted expenses (refunds subtracted per currency, contributions for "paid"). No `groupId` =
  all current groups plus personal ledgers. `requireMember` first when `groupId` is given;
  personal-ledger `groupId` 404 (same as history). Never sums across currencies.
- Frontend `analytics` route: filters, one section per currency, horizontal bars drawn with CSS
  (no chart library) **plus** an accessible table carrying the same numbers; empty periods and
  negative-after-refund values stay readable.
- Tests: totals reconcile with the H05 CSV for the same filter after an edit, a refund and a delete;
  mixed currencies stay separate; non-member 403.

**Saved split rules (P05)**:

- V32: `split_rules(id, owner_id→users, group_id→groups cascade, name varchar(80), split_type,
  inputs jsonb, version bigint, created_at, updated_at, deleted_at)`, partial unique
  `(owner_id, group_id, lower(name)) where deleted_at is null`. Rules are private to their owner.
- Supported types: **EQUAL (participant set), PERCENTAGE, WEIGHTS** — ratio rules that survive
  amount changes. EXACT/ADJUSTMENTS are not saveable (they are amounts, not ratios).
- `/api/groups/{groupId}/split-rules` list/create, `PUT/DELETE /{ruleId}` at `expectedVersion`
  (bind `{groupId}` for the personal-ledger guard; member-only; archived 409 on writes).
- Expense editor: "Apply saved split" select and "Save this split as…" for the three supported
  types. Applying prefills the draft only — never posts. If a rule names someone who is no longer an
  active participant, show a review message and do not apply silently. Changing or deleting a rule
  never touches existing expenses.
- Tests (red first): HTTP CRUD, version conflict 409, non-member 403, another user's rule invisible,
  EXACT rejected 400; rendered: apply prefills percentages that survive an amount change, removed
  participant → review message and no silent apply, save-as from the current draft.

## Rollout

Drain old backends before V32 (only P04+P05 has a migration). H07-A changes how the frontend is
cached: the first deploy installs the service worker; later deploys update on the next navigation.
Rollback of the PWA = a deploy that serves `ngsw.json` as missing/`safety-worker.js` (record the exact
steps in the H07 plan). Translations stay Beta.
