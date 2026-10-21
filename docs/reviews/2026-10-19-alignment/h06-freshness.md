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
