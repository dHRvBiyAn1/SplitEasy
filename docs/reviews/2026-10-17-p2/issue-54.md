# Issue #54: mobile account navigation

## Result

The Pocket Objects shell already provides a Profile item in its labeled four-item mobile/tablet navigation dock, shown through 1024px. Profile exposes Sign out, which uses the existing session reset, removes the stored access token and user, and routes to login. The persisted sidebar preference does not affect this navigation. No application code or duplicate implementation-level test was needed.

## Browser verification

Ran the local Angular development server at `http://127.0.0.1:4254` and exercised it in headless system Chromium (`/usr/bin/chromium`) using Playwright. The browser used a synthetic account and intercepted `/api/**` with empty JSON responses; no production account or API was used.

- At 390px, 820px, and 821px, repeated keyboard navigation (focus + Enter) with `spliteasy.sidebar` set to both `collapsed` and `expanded`.
- At all six combinations, the Profile link was visible and had the accessible name “Profile”; activation reached `/profile`, where the Profile heading and Sign out button were visible.
- Sign out by keyboard routed to `/login` and removed `spliteasy.accessToken` and `spliteasy.user` from local storage.
- Repeated the full Profile → Sign out path with pointer clicks at 390px, 820px, and 821px.

The browser script and dev server were temporary files/processes outside the checkout; the checks printed a success line for each of the nine paths.

## Repository checks

- `cd backend && JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` — passed, 138 tests.
- `cd frontend && npm test -- --watch=false` — passed, 110 tests.
- `cd frontend && npx prettier --check "src/**/*.{ts,html,scss,json}"` — passed.
- `cd frontend && npm run build -- --configuration production` — passed.
