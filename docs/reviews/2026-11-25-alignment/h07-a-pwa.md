# H07-A — installable PWA, app shell only (2026-11-25)

Plan: `docs/superpowers/plans/2026-11-25-alignment-h07.md`. Frontend only, no migration.

## Red → green
- `core/pwa/pwa-update.service.spec.ts` (4 cases): failed first (module missing), then 4/4 pass.
  Covers: VERSION_READY does nothing until the next navigation, then activates and reloads once;
  other version events/navigation without a ready update do nothing; `unrecoverable` reloads once
  per tab session; disabled worker does nothing.

## Checks (Node 22.17.1)
- Full frontend: 71 files / 462 tests pass. `prettier --check src` clean. Production build ok
  (initial 628.86 kB; the existing >500 kB budget warning was already present, +~7 kB from the worker).
- Build emits `ngsw.json`, `ngsw-worker.js`, `manifest.webmanifest`, `icons/*`. No `dataGroups`.

## Real browser check
Chrome (headless, own `--user-data-dir=/tmp/h07/profile`, puppeteer-core outside the repo) against the
production build served locally with a stub `/api`:
- manifest linked and parsed (name, start `/dashboard`, standalone), all 3 icons `image/png`,
  `theme-color` `#286970`.
- worker `activated`, page `controlled`, 63 cache entries, none under `/api/`; two `/api/health`
  fetches both reached the server (not served from cache).
- Server stopped, page reloaded: cached shell rendered (title + login content), no page errors;
  `/api/health` then failed with 504 from the worker (not cached).
- Not verified: install prompt / installed standalone window, real device, Safari, update flow
  (VERSION_READY → next navigation) in a browser (unit-tested only). Icons are generated from a simple
  placeholder mark (no vector brand mark exists in the repo) — design review recommended.

## Deployment note
No `_headers` in the repo; Cloudflare Pages defaults to `max-age=0, must-revalidate` for static files,
so `ngsw.json`/`ngsw-worker.js` are revalidated. Nothing changed. Rollback steps are in the H07 plan.

## Agents.md delta
- Add to Frontend Conventions: **PWA (H07-A)**: `@angular/service-worker` is pinned exact (21.2.17, bump
  with Angular); production-only worker caches the app shell/i18n/icons, **never API data** (no
  `dataGroups`); `PwaUpdateService` applies a new version at the next router navigation, one reload on
  `unrecoverable`. Rollback = serve `safety-worker.js` as `ngsw-worker.js` (see H07 plan).
- Progress Log: `- [x] Alignment H07-A installable PWA (2026-11-25) — manifest/icons, app-shell service worker with no API caching, update-on-next-navigation; frontend 462/71, build ok, real Chrome check. Slices 2–4 (read cache, queue, conflict UI) remain.`
- Stack: add `@angular/service-worker`.
