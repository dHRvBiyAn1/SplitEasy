# Dashboard refresh ownership (#50)

Baseline: `origin/dev` f68d818. A refresh previously captured the cache generation without advancing it, so concurrent same-session refreshes all passed the data, error and finalize guards. Advancing the existing generation at refresh start gives every request exclusive ownership. `clear()` still advances it and resets all state at explicit invalidation and session boundaries. No cancellation stream or new dependency is needed.

## Regression evidence

Eight HTTP regressions exercise both completion orders for old success/failure and newest success/failure. They assert retained trusted data, error and loading while the newest request is pending and after it settles. Before the fix: seven failures, three passes (including two existing presentation tests). After the fix: dashboard and existing account/session isolation suites pass 33/33, including clear during a pending request, account changes, stale successes/failures, and current-session failures. The separate failed-initial-load `ensureLoaded()` issue (#46) is outside this change.

## Testing

- `npm test -- --watch=false --include=src/app/dashboard/dashboard.service.spec.ts --include=src/app/core/auth/session-isolation.spec.ts`: 33 passed.
- `npm test -- --watch=false`: 118 passed.
- `npx prettier --check "src/**/*.{ts,html,scss,json}"`: passed.
- `npm run build -- --configuration production`: passed without warnings.
- `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` (backend): BUILD SUCCESS, 138 tests, no failures/errors/skips.
- `git diff --check`: passed.
- Local end-to-end browser rehearsal: system Chromium against Angular development server, synthetic signed-in user and intercepted `/api/dashboard` responses. Eight combinations of old/new 200/503 and completion order passed through real HTTP callbacks and rendered group totals/retry notices, with no page errors. Angular's development debug API triggered refreshes; this does not claim real mutation-button or live-backend coverage.

Session-local raw logs and browser reproduction source: `/workspace/issue-context/issue-50-{red,green,frontend,format,build,backend,browser}.log` and `issue-50-browser.py`. No production data or deployment was used.
