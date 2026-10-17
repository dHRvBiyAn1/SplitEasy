# Planning-run verification

Run began 15 October and continued 16 October 2026. This is evidence for an audit/design task, not a release certification.

## Repository boundary

- Worktree attached to this chat: `/Users/dhruv/.codex/worktrees/b4b9/SplitEasy`.
- `git fetch origin dev` completed; new branch `feat/ui-redesign` starts at `597fc93e8b8219a21d46435206fe34b9111da367`.
- Product files, backend, package/lockfile, API and schema were not edited. Tracked edit is only the Agents.md planning note; new artifacts are under docs. No commit/push/PR/merge/deploy/publish.
- Original checkout was used only for reading prior audit evidence, not switching branches or editing its source.

## Existing application baseline

- Java21 backend compiled and booted on 18089 against a new disposable PostgreSQL 16 database on 55439; Flyway applied all eight migrations and local health returned UP.
- Node 22 locked frontend dependencies installed locally, unchanged frontend served on 4209 with a temporary local proxy.
- Frontend Vitest: **80/80 tests, 14/14 files**, 5.83s. No new product tests or fixes were applied.
- Production Angular build completed: **417.91kB raw initial, 105.08kB estimated transfer**. These are Angular build figures, not measured field loading/INP. An initial sandboxed build aborted before diagnostics; the permitted rerun completed outside that process restriction.
- Full backend test suite and deployment suite were **not rerun** for docs/prototype-only changes; they are explicit implementation/PR gates in the roadmap.

## Current UI audit

- 35 fresh screenshots with supporting DOM snapshots and [SHA256 manifest](evidence-manifest.json). Two live public auth captures; all authenticated screenshots from synthetic local fixtures.
- Observed local current UI at desktop 1440×1000 and mobile 390×844. Current app long-content fixture had 897px document width at 390px. Actual clipped viewport and geometry JSON retained.
- Source inventory covers every current route/modal. Browser sampling includes shell/rail, empty/populated dashboard, expense equal/exact/percent/edit/breakdown, group empty/error/settings/rename/confirmation, member error, settlement empty/direct/simplified/confirm/validation/success, profile/edit and themes. Other edge states are explicitly source-only or prior evidence in the audit.
- No real-user production mutation. Synthetic fixture credential file, seeding script and temporary proxy were removed from `/private/tmp` after use.

## Design studies

- Four different compositions: Social Club, Night Atlas, Weekend Press, Pocket Objects. Alternate layouts are separate files, replacing the original lightweight palette-only comparison.
- Saved desktop 1440×1000 and mobile 390×844 screenshots, Social Club light/dark, example expense modal and [four-way gallery](../../concepts/ui-redesign/screenshots/four-designs.jpg).
- Eight small-phone/tablet width checks across four designs at 320px/768px: **zero remaining document overflows**. Weekend Press initially 327px at 320px; DOM evidence identified the masthead+date min-content width; reducing only the ≤360px masthead from 59px to 52px corrected it. No global horizontal clipping was added.
- Native dialog accessible name, inside focus, member-specific percentage labels and Escape close were exercised on a representative alternative study. Shared prototype behavior is the same across three alternatives. All “save” actions are illustrative; no accounting/API integration is claimed.
- JavaScript syntax checks passed for study.js, alternatives.js and cursors.js. Local HTML asset/link existence and duplicate-ID checks passed. `git diff --check` passed.

## Cursor supplement

- Four original SVG/native-tip + animated-companion options, no cursor library installed.
- Browser observation confirmed Split Tip’s custom SVG URL/hotspot, visible split follower, and `cursor:text` on input. Reduce motion set follower hidden/display none. Mobile study width 390px matched document width 390px.
- Desktop/mobile cursor comparison screenshots retained. OS-level reduce-motion/forced-colors and real touch/pen/hotspot tests remain future product integration gates; a preview toggle is not proof of an OS preference test.

## Cleanup and limitations

- No audit frontend/backend listener remained on 4209/18089 at final process check.
- Attempted to stop `spliteasy-ui-audit-db`, but Docker Desktop’s socket was unavailable by then. **Container cleanup could not be confirmed**. It was created with `--rm`, no volume and synthetic data only; no Docker service was started just to clean it. If it reappears after Docker restarts, stop that exact named audit container.
- Local docs preview remains a convenience; files and screenshots remain usable from disk if its server stops. No live site was published.
- No production performance trace, real-device Safari session, screen-reader certification, exhaustive browser/state matrix or complete visual implementation is claimed. Those checks are specified in the provisional roadmap.

## Palette, dark theme and trail follow-up — 16 October 2026

- Added three palettes per design (12 total), each with light/dark surfaces and illustration roles (24 combinations). `colors.html` compares every option and opens its matching interactive demo.
- Resolved the reported demo gaps: dark mode was previously limited to Social Club, and the cursor was previously confined to the supplement's sample boxes. All four main demos now expose a working theme toggle and three trailing cursors. The alternative profile Theme selector is wired too.
- Browser evidence: `palette-checks.json` (24 desktop views), `palette-mobile-checks.json` (24 views at320px), `preview-responsive-checks.json` (four designs at320/768px), `preview-interactions.json` (theme controls, dark dialogs and three trails/reduction). No horizontal overflow in those checks.
- Palette-role math: 192 normal-text contrast comparisons at ≥4.5:1. Actual rendered-text inspection: 1,872 instances across24 views; Peach/Seafoam caption and avatar contrast failures reproduced then corrected. The check composites solid/translucent CSS backgrounds and does not model image/gradient pixels or certify all states. `palette-rendered-contrast.json` records final results.
- JavaScript syntax and local-reference checks cover the static artifacts. No Angular/backend code or dependencies changed; product suites were not repeated for the static study controls. Existing full-suite results above remain baseline evidence, not a claim that these new controls were production-tested.

## Selected Pocket Objects refinement — 16 October 2026

User selection: Pocket Objects, Seafoam & Petrol default, Silk Ribbon, corresponding touch feedback, labelled icons/selection in the mobile/tablet dock, and liquid-glass hover/press. Updated the static study and recorded the selection in the written design/plan; no Angular or backend implementation changed.

- `pocket-responsive-checks.json`:30 combinations (3 themes ×2 modes ×5 widths:320/390/768/1024/1440), no overflow. Dock visible through1024px, four icons, exactly one current item, and target heights≥44px when visible.
- Browser control checks: theme changes retain mode; explicit Light/Dark and selected colors persist through navigation without a query override. Expense/Profile select their dock item; Escape restores the prior Groups/Overview destination. Native Appearance dialog is reachable from header and Profile.
- `pocket-interactions.json`:480 rendered-text comparisons across the six dashboard and six Appearance views, zero failures in the composed-background check. This excludes image/gradient/backdrop pixel sampling and full accessibility certification.
- Actual hover inspection confirmed the navigation highlight at opacity1 and `blur(16px) saturate(1.2)`. Reduced motion hides the glass sheen, clears the trail overlay and disables the touch preview. Silk Ribbon is fixed for the selected study.
- The touch rehearsal renders the bloom/ribbon through the shared canvas path. An initial mouse-position join was reproduced and corrected: a new touch/sample resets its points before rendering. Passive native TouchEvent handlers preserve scroll/pinch and clear on multi-touch/cancel. Native hardware touch and iOS/Android performance are not claimed as verified by the desktop browser rehearsal.
- Saved selected desktop light/dark, tablet dark, mobile light/dark, Appearance and animated-touch snapshots. Static JS syntax/local references checked. Product suites were not repeated for documentation-only prototypes.
