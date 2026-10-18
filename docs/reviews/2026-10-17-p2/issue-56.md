# Issue #56 — shared dialog accessibility

Verified 2026-10-18 against dev baseline `f68d818`, using local Angular and system Chromium with synthetic, intercepted API responses. No production service or data was used.

The Pocket Objects redesign already supplies named headings, initial heading focus, CDK Tab trapping/restoration, Escape/Close behavior and scroll-contained mobile panels. Two remaining gaps were found:

- Selecting a settlement removes the focused row; Chromium leaves focus on `body`. Back from confirmation similarly removes its focused button. The shared shell now uses Angular's render callback and the existing CDK initial-focus helper to recover focus only when it has fallen to `body`; rendering preserves focus already on a control.
- The skip link was outside the inert shell. It now lives inside the shell so background protection includes it.

## Regression evidence

The shared-shell regression replaces a focused button with an input and then replaces the focused input with the selection step. It also asserts that an ordinary render preserves the focused button. jsdom has no layout, so only CDK's focusability check is supplied by the test; real focus movement and DOM replacement are exercised. With the baseline shell the removal assertion fails (`body` instead of the heading); the fixed shell passes.

Run the retained browser regression from the repository root after starting `npm start -- --host 127.0.0.1 --port 4256` in `frontend`:

```sh
python docs/reviews/2026-10-17-p2/issue-56-browser.py
```

Requires Python Playwright and `/usr/bin/chromium`. `SPLITEASY_TEST_ORIGIN` can override the local origin. The script rejects unexpected API routes/methods and performs no API mutations.

Chromium passed at **1440×900**, **320×568** and **844×390** for New group, New expense, Edit expense, Add member, Settle up and Appearance:

- Accessible dialog name and focused visible heading on opening; Chrome accessibility-tree name checked separately from locator matching.
- Repeated Tab and Shift+Tab cycles remain inside, including expense Equally/Unequal/Percent changes and settlement selection → confirmation → Back → confirmation.
- Inert background rejects programmatic launcher/skip-link focus; Chrome's accessibility tree excludes the background skip link.
- Escape and the named Close button restore each connected launcher; body overflow and inert state are restored.
- Dialog bounds fit each viewport; short/mobile expense panels scroll internally while the background stays locked. Keyboard traversal reaches their controls.
- No JavaScript page errors or console errors.

## Required checks

- Backend: `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` — **138 passed**, no failures/errors/skips.
- Frontend: `npm test -- --watch=false` — **111 passed** in 23 files.
- `npx prettier --check "src/**/*.{ts,html,scss,json}"` — passed.
- `npm run build -- --configuration production` — passed.
- `git diff --check` — passed.

Session logs are retained outside the checkout in `/workspace/issue-context/issue-56-{red,frontend,backend,format,build,browser}.log`; those local paths are not published attachments.

Limitations: headless Chromium with synthetic read responses; no real screen reader, physical phone/virtual keyboard, live backend mutation or production test. Existing connected-launcher restoration is covered; route/session removal of a launcher retains the existing main-focus fallback. No dependency, backend, API or schema change; no merge or deployment.
