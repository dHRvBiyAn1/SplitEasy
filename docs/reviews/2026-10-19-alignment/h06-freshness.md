# H06 — predictable cross-device freshness

Base: `e5b219773e54f890e56358f2a57de28f56d4bdff`. Execution design: [H06 plan](../../superpowers/plans/2026-10-19-alignment-h06.md). Workflow: project-local Matt Pocock TDD/diagnosing-bugs, Ponytail full. Authorized seams: rendered Angular plus controlled HTTP. Root owns independent review, real two-client browser rehearsal, integration and PR publication.

## Result

Authenticated NavigationEnd, visible-document transitions and window focus revalidate the existing overview and active group independently. Pending foreground reads retain their owner; successful writes still force newer reads. Overview response versions reject superseded successes, errors and finalizers. Session boundaries cancel pending overview/group work and synchronously clear loaded records and settings drafts. Route changes retain the existing group/detail cancellation boundary.

Transient overview/group failures retain confirmed data with explicit stale status; initial failures show unavailable states, including the group tray. Group access denial removes previously rendered content. Settings eligibility stays disabled while financial reads are pending/unavailable and offers an independent Reload group action. Foreground refresh retains selected expense/detail feedback and re-fetches successful cached detail; mutation refresh preserves the existing receipt-collapse/cache-invalidation behavior. Pending detail reads survive failed foreground refreshes. Existing delete conflict guidance remains attached to its expense during foreground refresh; changing group routes clears detail/delete feedback so an A → B → A navigation cannot resurrect an earlier conflict.

No backend/API/migration/dependency/auth-session implementation or editor/write-intent changes. No polling, WebSocket, durable cache or offline support.

## Regression evidence

External logs: `/workspace/issue-context/alignment-implementation/batch3/H06/`.

Sequential public-seam loops established coalesced overview ownership and reproduced missing foreground/navigation requests, group/settings refresh depending on overview success, stale content after access/account changes, missing settings retry, and cancellation of pending selected detail during foreground refresh. `red1.log` is the new revalidate API's compile-time red; later reds are HTTP/rendered assertions. The first settings account-boundary fixture masked loaded content behind an active spinner; `red13-corrected.log` checks fully rendered settings and reproduces the issue. No false red claim is made for the original `red13.log` run.

The first complete suite found an implementation regression: mutation refresh incorrectly retained an expanded receipt and issued an unmatched detail GET, causing TestBed teardown cascades (25 failures). The existing public rendered `group-presentation.spec.ts` isolated it in `mutation-collapse-red.log`. Restoring force-mutation collapse while retaining foreground selection passed both group suites (19/19, `mutation-collapse-green.log`). Final complete verification below supersedes `frontend-full.log` and the earlier production build. The final pending-write test verifies cancellation, collapsed receipt and explicit re-expansion after failed group refresh.

A final route-generation regression reproduced old delete feedback reappearing after A → B → A navigation (`route-boundary-red.log`: 13 pass/1 fail). The route-change handler cleared records but retained detail/delete feedback; clearing those two signals at that existing boundary passed the targeted suite (`route-boundary-green.log`: 14/14). This leaves same-route foreground retention unchanged. The complete verification below includes this final two-line fix.

## Exact final verification

All commands ran from `frontend/` on Node 22, after the final source changes:

| Command | Result | Log |
| --- | --- | --- |
| `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false` | 32 files, 252/252 tests; exit 0 | `frontend-route-final.log` |
| `npx prettier --check 'src/**/*.{ts,html,scss,json}'` | All source files pass; exit 0 | `prettier-route-final.log` |
| `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build --configuration production` | Production build passes; exit 0 | `production-build-route-final.log` |
| `git diff --check` (repository root) | Pass; exit 0 | `diff-check-route-final.log` |

A first formatting pass needed a second pass on three chained test calls; the final full-source check passes. No application source changed after final verification; subsequent edits record evidence/instructions only. Backend tests were not rerun for this frontend-only change; root approved reuse of the verified frozen V11 JAR for the packaged rehearsal.

## Integration and limits

Shared files: App freshness listeners/group-tray states; DashboardService read notifications/cancellation; GroupDetail read lifecycle/template. E02 owns its separate DELETE expectedVersion hunk in GroupDetail; merge that hunk without replacing the refresh lifecycle. A02 confirmed no App/session-isolation test overlap. AuthService, SessionService and SecurityConfig are unchanged. Existing mutation callers keep refresh(). GroupSettings removes its redundant toggleSimplify reload because refresh() now independently re-reads the group immediately.

No schema rollout or migration ordering requirement. Revert the frontend change to roll back; server state and recorded money are unchanged. This is revalidation on foreground/navigation/write, not immediate push synchronization. Browser, physical-device and production delivery are not claimed by this worker. Root's pending real two-client focus/navigation rehearsal is a separate gate before PR publication; no push, PR, merge or deployment was performed here.


## Root packaged/browser verification — 21 October 2026

At application candidate `4421aab9ec0dfd1bc103855a36ccf7bc395a1b4b`, real production Angular and Chromium passed at1280 and390 pixels against an owned packaged V11 backend/PostgreSQL instance. A separate authenticated HTTP client created expenses; native foreground focus and router navigation refreshed the rendered history and selected expense. Each width produced17 reader API requests with no fixture data responses, page errors or horizontal overflow. Owned application, PostgreSQL container/volume and static server were cleaned up. Backend source is unchanged; the frozen V11 JAR SHA256 is `849acf18992c7c915a8a81659ad49127816fb709e3673919bef0464c54ea1aa5`.

The initial browser attempt failed because Playwright's headless focus emulation kept every tab focused. An empty-page probe reproduced the missing events; disabling focus emulation after application navigation and on the away tab restored native blur/focus. No synthetic app events or production source changes were used for this correction. Root artifact: `/workspace/issue-context/alignment-implementation/batch3/H06/real-browser-diagnostic.json`; screenshots alongside it. Independent Standards/Spec review and final combined source checks remain separate gates.

## Independent review and integrated checkpoint

Independent Matt Pocock Standards and Spec reviews each reported **zero material
findings** through `15ebc09`, including shared E02 GroupDetail hunks. The final
DELETE regression matches path plus method while E02 independently asserts its
expectedVersion query. No application semantics changed in that test adjustment.
The combined E02/H06/A02 candidate passed279 frontend tests; the final batch
integration report records subsequent A02 review remediation and acceptance.
Native foreground/navigation browser acceptance was also repeated successfully
with E02/A02 code in the real combined production build. Exact final PR-head CI
is recorded by GitHub; no remote merge or deployment is claimed here.


## PR #102 explicit read retry review follow-up — 23 October 2026

Group Try again now uses the existing non-forced reload, and shell/dashboard
overview retry controls use revalidate(). Previously these read retries selected
the mutation path, collapsing another active receipt and clearing its actionable
detail feedback. Mutation refresh callers keep their forced invalidation. No
backend, API, schema or dependency change.

Rendered Angular with controlled HTTP locks down the selected receipt and detail
error through group retry, including a successful explicit detail retry. The two
overview controls retain the active receipt while reads are pending and display
the refreshed $30.00 paid amount in that receipt after success. The predecessor
group red (`20-group-retry-red.log`) contains one meaningful failure plus teardown
cascades; `21-group-retry-green.log` passes 15/15. The initial overview red
(`22-overview-retry-red.log`, repeated in 23) likewise has only the shell failure
as meaningful evidence. Request draining before assertions isolates clean shell
red 24 (one failing test), shell green 25 (1/1), then dashboard red 26 (1 fail, 1 pass).
Both control fixes plus group retry pass 17/17 in `27-read-retries-green.log`.

Final verification from frontend/: Node22 `ng test --watch=false` passes 33 files
and 255/255 tests (`29-retry-full-tests.log`); full-source Prettier passes
(`33-retry-prettier-final.log`, after its required second formatting pass);
Node22 production build passes (`31-retry-production-build.log`); root
`git diff --check` passes (`34-retry-diff-check.log`). A subsequent test-only
formatting pass changes whitespace only. Root owns independent review and the
real-browser transport-drop retry rehearsal. No push, remote review action,
merge, preview edit or deployment by this worker.
