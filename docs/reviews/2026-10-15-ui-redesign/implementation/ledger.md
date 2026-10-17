# Implementation ledger — Pocket Objects

User authorized Angular implementation on 16 October 2026. Source plan: docs/superpowers/plans/2026-10-16-ui-redesign.md; selected design: docs/superpowers/specs/2026-10-15-ui-redesign-design.md.

Workspace: existing isolated feat/ui-redesign worktree, HEAD597fc93. Keep all existing proposal artifacts. No commit/push/merge/deployment requested.

Execution: inline. Stages: (1) tested independent palette/mode + motion foundation and local fonts; (2) shell/tray/dock and Pocket dashboard; (3) group/detail/settings, all modals, auth and profile; (4) owned Silk Ribbon/touch and bounded glass; (5) full checks, browser evidence and final review.

Pre-flight: ThemeService is the existing mode authority; extend it with an independent palette rather than a parallel preference service. SessionService remains identity authority; decorative effects dispose on session reset. ModalService remains mutation/modal authority; dock selection derives from route + active modal and therefore restores automatically. Existing dashboard payload remains the only overview data source. Current modal shell lacks a focus trap/name; fix that shared boundary before using the new navigation.

Ruling: this explicit implementation request approves the selected spec/plan execution; no additional visual approval gate is needed.
Ruling: preserve working business behavior and APIs. Optional payment history, new account preferences/API, full group partial-success workflow and split draft preservation are outside this visual task. Include fixes needed for truthful UI/error handling or safety, but do not expand backend scope.
Ruling: use CSS/native canvas and existing CDK rather than installing Motion: all selected effects are covered without an additional dependency.
Ruling: keep this reviewable ledger in docs instead of commit-based scratch tooling because the user has not authorized commits; unfinished changes must remain reviewable in this worktree.

- [x] Foundation + meaningful regressions
- [x] Shell, navigation and dashboard
- [x] All remaining screens/forms/dialogs
- [x] Ribbon/touch/glass ownership and accessibility
- [x] Production build, full suites, responsive/browser verification and review

Foundation: clean baseline 80/80. Four behavioral RED failures confirmed missing default palette/native color-scheme and dialog name/scroll lock. Motion contract first failed because absent; implementing OS-authoritative/device-reduced preference. Fetch origin/dev found0 incoming commits. Existing isolation verified. Fonts converted from licensed local source assets to WOFF2 in public/fonts (no runtime network font dependency).
Foundation GREEN:9/9 focused tests (palette/native-mode4, motion3, dialog2). Runtime defaults/invalid stored values and independent mode/palette persistence covered. New standalone display-only Money preserves exact cents and keeps neutral totals separate from net status. Converting fonts produced approximately110kB total WOFF2 across the two families. Shell regression added before replacement: missing labelled icon dock/selection.

Stage2: shell regression RED (missing four-icon dock) → full suite92/92 GREEN. Replaced sidebar with compact header, named Groups tray and route/modal-derived dock selection; adapted session-isolation rendering assertion to open the new tray, retaining Alice/Bob privacy assertions. Dashboard uses the same payload in the selected collection/balance/People composition, with neutral settlement amounts and explicit net-zero/open-debts wording. Full native production compiler works outside sandbox; in-sandbox production/development abort in native malloc, while outside-sandbox production succeeds. Use elevated compiler/server execution, not dependency replacement. Build reported a50-byte dashboard style warning; removed redundant decorative shadow to stay within existing budget.
Stage3: group/detail/settings surfaces, all modal forms, authentication and profile migrated to shared geometry/tokens. Member inputs and settings switch now named, selected chips expose state, auth gains visible field labels and reveal/validation UI. Auth illustration corrected to an exact3-way sample. Profile uses the same independent Appearance panel and explains local-only edits.

Stage4: single native canvas Silk Ribbon/touch implementation, no package changes. Four ownership regressions cover synchronous account clear, reduction, pinch and destroy. Browser preview starts a visible canvas and Reduce motion hides it immediately; native fine-pointer cursor and glass hover observed. Physical touch and frame/input performance on real devices remain unmeasured, not represented by the rehearsal.

Stage5: frontend105/105 across22 files, backend138/138 and deployment12/12 pass; Prettier check and git diff --check pass. Final production build has no warnings,470.71kB initial raw /114.67kB estimated transfer (+9.59kB vs audited105.08kB). Local WOFF2 total 112872 bytes. See verification.md and the machine-readable theme/responsive/contrast/build evidence.

Final review: one fresh read-only reviewer found four important retained presentation/state defects: initial dashboard failure retry loop, prior-group callbacks rendering the wrong route, old expanded receipt after edit, and net-zero “Settled up” wording. All accepted and fixed. Four behavioral regressions failed before correction, then the full suite passed. Group pages now cancel route/account/destroy work and previous reads, render combined reads, collapse invalidated detail; App initial fetch uses untracked. Minor findings (profile selected-state semantics and44px email removal) also fixed. Optional backend profile/history/draft/partial-group workflows remain excluded; no financial/API/schema change.

Runtime evidence: all six palette/mode combinations and System/native control scheme; mobile/tablet dock one active state, Escape restores Expense→Overview and launcher focus; named dialog Tab trap/inert/scroll lock; group create+member add+rename+simplify; invalid registration/login; Rae empty account isolated from Ada; exact expense creation→percentage edit→deletion; partial 10-dollar settlement; long multilingual200-char receipt/100-char group and near-cap value at 320px; landscape dialog/actions fit. Temporary cap fixture removed. Final local review app on4320, backend 18089, disposable PostgreSQL 15439.

Execution scope is complete for the selected local implementation. Hardware touch, Safari/Android, throttled frame/input traces and real browser400% zoom were not available in this tool run and remain release QA, explicitly not certified. Desktop viewport checks and320px reflow are not equivalent to those checks. No commit/push/PR/merge/deployment.


Publication handoff — 17 October 2026: user explicitly requested commit and push, then wait for CI, then create the PR. Target remains dev. The repository permits workflow_dispatch on the feature ref, so run that CI after push and create the PR only after success. Retain the worktree. No merge/deployment is authorized. Fresh pre-commit backend/frontend/deployment checks and formatting are being run against the implementation tree.

Pre-publication checks completed: Node22 frontend105/105 plus Prettier, Java21 spotless:check verify with138/138 tests, deployment regressions12/12 and git diff --check passed. ShellCheck/Actionlint binaries are unavailable in this local shell; unchanged release scripts are checked by the dispatched CI workflow. Production build had already passed on the identical application sources with no warnings; branch CI repeats it before PR creation.


PR #70 Codex follow-up — 17 October 2026: the user authorized resolving the review and then merging into dev. The single P2 finding was reproduced: an empty overview group list hid a valid scoped Add/Edit Expense form, even after group members loaded independently. Four HTTP/component regressions failed before correction (create/edit × pending/failed overview). The empty-state gate now also requires absence of an explicit groupId. Five focused tests pass, including correct POST/PUT cents payloads and the genuine no-group Create group action. Full frontend110/110, Prettier and warning-free production build pass. PR CI will be verified before the user-authorized merge into dev; APIs and financial calculations are unchanged.
