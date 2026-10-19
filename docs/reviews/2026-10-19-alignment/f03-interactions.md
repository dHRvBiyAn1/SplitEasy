# F03 honest profile and recoverable interactions

Scope: foundation plan tasks 4–6. Branch `fix/alignment-f03-honest-interactions`,
base `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca` (`origin/dev`).

## Behavior

- Server-provided name and login email are read-only; the unused local auth updater is removed.
  Phone, avatar and currency preferences remain owned by the immutable account ID on this device.
  Copy explicitly says currency preferences do not change or convert recorded amounts.
- Expense detail failures offer a deliberate retry for the current expense. Failed deletion keeps
  the expense and shows server guidance on its original row, including former-participant re-add conflicts,
  even after collapsing that expense or expanding another.
- Required expense member/detail loads have visible loading/error/retry states. Retry retains typed
  description, amount and date, guards the selected group generation, and never submits a write.
  Add-member has a genuine initial GET path, now with its own explicit roster-load retry.
- One shared pending signal blocks Escape, backdrop and Close/Cancel/Done handlers during dispatched
  writes in expense, settlement, group and member dialogs. Outcomes/destruction clear pending;
  session reset force-closes and cancels callbacks. Captured session versions reject stale submissions
  and retries before an old view is destroyed. New-group sequential additions remain pending until
  their aggregated outcome; the created group and failed-only retry behavior remain intact.
- Settlement choices and confirmation include full name/email. Running split totals are polite live
  regions; expense dates expose the full date. Existing focus trapping/restoration and inert background
  behavior remain in place. Payment operation identity survives uncertain failure and modal reopen.

## Regression-first evidence

Tests use the user-approved rendered Angular and controlled HTTP interfaces. Existing complementary
service/route tests were retained. Each new behavior was exercised red before its minimal fix, then
focused green before the next scenario:

| Slice | Observed red symptom | Green evidence |
| --- | --- | --- |
| Profile truth | Edit label/identity controls misleading | Profile/auth/preferences 12 passed |
| Current-group retry | No retry button after 503 | Expense suite passed |
| Detail retry | Silent failed detail, absent alert | Group detail suite passed |
| Delete conflict | Silent 409, absent lifecycle guidance | Group detail suite passed |
| Pending expense | Escape/backdrop closed dispatched write | Expense suite passed |
| Add-member roster | Unhandled initial 503, absent retry | Member rendered test passed |
| Settlement identity | Duplicate names lacked full name/email | Settlement suite passed |
| Live totals/date | Absent polite region/full accessible date | Expense/group suites passed |
| Edit retry | Successful prefill overwrote typed draft | Expense suite passed |
| Group destruction | Pending state leaked after destroyed request | Group suite passed |
| Old-session draft | Old expense could submit before destruction | Expense suite passed |
| Loading copy | Member load described only as expense details | Expense suite passed |

The rendered global-host tests additionally exercise all four mutation paths through the real shared
shell, error→close, reset→forced teardown/cancellation, and payment operation-ID reuse after uncertain
failure and dialog reopen. Existing session-isolation, queued-group-addition, route-generation and
payment retry tests remain part of the full suite. Legacy tests using the removed local identity
updater now use authoritative login responses or device preference updates.

## Final verification

Run from this worktree with Node 24.19.0 (CI remains Node 22):

- `cd frontend && npm test -- --watch=false`: **213 tests, 30 files passed**.
- `cd frontend && npx prettier --check 'src/**/*.{ts,html,scss,json}'`: passed.
- `cd frontend && npm run build`: production build passed.
- Parent-coordinated `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify`
  with Java 21: **207 tests, zero failures/errors/skips**, BUILD SUCCESS. Backend source is unchanged.
- `git diff --check`: passed.

Detailed command output and red/green logs are retained in
`/workspace/issue-context/alignment-implementation/F03/` (`frontend-full.log`, `prettier.log`,
`build.log`, `backend-full.log` and per-slice logs).

## Browser evidence and boundaries

`browser.py` serves the actual production build locally on port 4303 and drives system Chromium
(`/usr/bin/chromium`) with Python Playwright at **1280×844** and **390×844**. Both passed:

- Login → profile → device preference save → logout → login: identity remains server-provided and
  local phone preference returns; no name/email input exists.
- Detail GET 503 → keyboard Enter on retry → loaded detail; DELETE 409 retains row and re-add guidance.
- Member GET 503 → explicit keyboard retry retains description/amount; running total has polite live
  semantics; expense date exposes October 16, 2026.
- Delayed expense POST: Escape/backdrop keep the dialog, Close is disabled, saving feedback persists;
  503 restores closure and retains the draft.
- Duplicate-name settlement choices identify distinct emails; delayed payment cannot close via Escape;
  uncertain failure then reopen reuses the same operation ID; confirmed retry closes once.
- No page errors or horizontal document overflow at either viewport.

All browser API responses, including auth/dashboard/group/expense/payment and 409/503 outcomes, were
intercepted and controlled; backend lifecycle correctness is **not** asserted by these browser checks.
Angular rendering, routing, keyboard input, focus, layout and localStorage were real. Browser artifacts:
`browser-1280.json`, `browser-390.json`, `detail-{1280,390}.png`, `pending-{1280,390}.png` and `browser.log`
in the evidence directory above. An initial browser run needed its alert locator scoped to the dialog
because the correct background delete error remained visible in the DOM; only the harness changed.

Physical devices and real screen readers were unavailable. The browser checked accessible DOM semantics
and keyboard behavior, not actual screen-reader speech. Preferences remain local; there is no account
mutation endpoint, currency conversion or durable cross-reload operation identity. No dependency,
backend, OpenAPI, merge, deployment or production-data change is included.

## Review follow-up: expense-owned deletion feedback

The Standards review at candidate `1e41124a279d790fbfced95e8b5019f2f91ac59e` found that a delayed
DELETE failure was hidden after collapsing its row, or appeared under a different expanded expense.
The follow-up associates the failure with the originating expense ID and renders its named `role=alert`
inside that expense's list item outside the expandable detail. Expanding/collapsing rows no longer clears
that feedback. Existing retry, route/session cancellation and successful refresh behavior are retained.

One rendered-UI/HTTP regression first reproduced the absent alert after collapse (`review-collapse-red.log`),
then passed with the minimal ownership/rendering change (`review-collapse-green.log`, 5/5).
The next individual cases verified that Dinner's delayed 409 stays on Dinner while Lunch remains expanded
(`review-switch-green.log`, 6/6), then that successful DELETE 204 refreshes the list to remove Dinner and
retain Lunch (`review-success-green.log`, 7/7). No additional product scope was changed.

Final-content checks, with Node 24.19.0:

- `cd frontend && npm test -- --watch=false --include=src/app/groups/group-detail.component.spec.ts`:
  **7/7 passed** (`review-focused-final.log`).
- `cd frontend && npm test -- --watch=false`: **213 tests, 30 files passed** (`review-frontend-full.log`).
- `cd frontend && npx prettier --check 'src/**/*.{ts,html,scss,json}'`: passed (`review-prettier.log`).
- `cd frontend && npm run build`: passed (`review-build.log`).
- `git diff --check`: passed.

`review-browser.py` repeated the original production-build controlled-HTTP rehearsal at **1280×844** and
**390×844**, additionally delaying DELETE 409 until after collapse and then after switching to Lunch.
Both verified visible Dinner-owned server guidance, no alert under Lunch, and successful DELETE 204
removing only Dinner on refresh. The original profile, retry, modal pending, settlement identity and
operation-ID scenarios also passed; no page errors or document overflow. Output is `review-browser.log`,
with per-viewport JSON and screenshots in `review-browser/` under the existing evidence directory.
All browser APIs remain intercepted; backend source is unchanged, so the existing **207-test backend**
evidence above was retained without another backend run. No push, merge, deployment or new issue.
