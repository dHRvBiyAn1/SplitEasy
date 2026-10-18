# Issue #55: partial group member additions

Verified locally on 2026-10-18 against dev baseline `f68d818`.

The new-group modal converted each failed member POST to `null` and discarded the
aggregated results before closing. It now retains the created group ID, shows each
requested email with its outcome, and retries only failed additions in that group.
Successful additions remain visible across retries. Unknown accounts (404), existing
members (409), and transient failures receive friendly reasons. Results are announced
through a polite status region; View group opens the retained group.

The session cancellation remains after `toArray()`, so a session reset cancels both
the active member POST and queued additions without refreshing or navigating as the
next account. The original create and member-queue cancellation tests remain intact.

## Testing

- Regression first: the original implementation failed two focused tests because
  it displayed no result summary and offered no retry. The no-member create test
  passed before the change. Red evidence: `/workspace/issue-55-red.log`.
- `cd frontend && npm test -- --watch=false`: 114 tests passed, including four new
  component/real-service/HTTP tests for mixed outcomes, failed-only retry, retained
  successes, session cancellation during retry, duplicate-save protection, all-success
  results, and creation without member additions.
- `cd backend && JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify`: 138 tests passed; build and formatting passed.
- `cd frontend && npx prettier --check "src/**/*.{ts,html,scss,json}"`: passed.
- `cd frontend && npm run build -- --configuration production`: passed.

## Browser evidence

A local Chromium run at 390×844 used the actual Angular application and synthetic
API responses (no production requests or user data). It created a group with four
requested members returning 200, 404, 409 and 503. It verified all four visible
email/reason results, retried only the three failed emails, observed all-success
results, and used View group to reach `/groups/created-group`. Exactly one group
create POST occurred; no browser page errors occurred.

Local evidence references (not published attachments):
`/workspace/issue-55-browser.cjs`, `/workspace/issue-55-browser.log`,
`/workspace/issue-55-partial-mobile.png`.

This verifies frontend orchestration with intercepted HTTP, not a live backend
failure injection. The existing group/member API contract is unchanged. Retry is
available while the result dialog remains open; existing group screens can add
members after it is closed. No merge or deployment performed.
