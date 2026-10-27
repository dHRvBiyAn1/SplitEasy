# G02.1 — saved friends directory and authorized detail

Status: implementation complete, full local verification and independent reviews passed; publication/exact-head CI is the next gate. This first serial G02 PR targets dev from verified68bca60955a2c11c539a2f880e8275205215f5c1. PR107 merged at that commit, its tree equals reviewed9f220b4, and post-merge CI40347083397 passed. Personal expense CRUD and personal debt/payment/history remain later serial PRs.

## Behavior and scope

Private directed saved friends survive settlement, source-group access loss and group deletion. Save selects an active registered original financial participant from an authorized group under its write lock. Claimed aliases resolve to one account; guests require verified claim first. Saving grants no membership, ownership or reciprocal friendship. Responses expose only the saved authorized display label, not account email/phone or a live global profile lookup.

Directory/detail use one repeatable-read snapshot and existing canonical selected-mode projections over currently accessible groups. Amounts stay explicit per currency. Opposing USD obligations can total zero while open group obligations remain. No shared group data and failed reads stay distinct from settlement. Detail links to authorized group histories and settlement; it adds no personal expense or friend-only payment operation.

UI provides desktop/mobile Friends navigation, explicit roster selection/save/remove, readable per-group breakdowns, uncertain-write status reads and scoped foreground refresh. Group/route/session boundaries cancel requests and clear owned state. Confirmed detail removal returns to the directory. Lost detail-delete responses use an explicit owner-directory status read; no automatic write retries.

## Observable evidence

- Authenticated HTTP/PostgreSQL:12 friend workflows. Original save/detail/remove missing routes were observed red before implementation. Source membership, self/guest/foreign/inactive targets, directed isolation, currencies, settlement retention, selected mode, saved labels/access loss, capabilities and unsafe exact totals are covered.
- Queued source-member removal wins before save: waiting save reauthorizes to403, leaving directory empty.
- Repeated save concurrently removed after a skipped insert originally returned500; deterministic HTTP/SQL synchronization reproduces it. Corrected response is409 with reload/review guidance, without resurrecting the removed bookmark.
- Rendered routes/HTTP:13 new friend tests, plus existing shell assertions. Includes directory errors vs zero, save/remove confirmation, uncertain response review, A→B→A source/route/session cancellation, foreground coalescing, detail removal navigation and blank source cancellation.
- Independent checkpoint packaged V17→V18 plus112 real HTTP responses passed, including actual loopback SMTP verification/guest claim, alias deduplication, old monetary IDs/values/splits/versions and immutable JSON preservation, per-currency group reconciliation and access-loss/deletion privacy. Owned resources cleaned. The first harness compared receipt nanoseconds with persisted PostgreSQL timestamp precision; it was corrected to compare persisted pre/post reads. Only createdAt differed; no application change was required.

Final corrected JAR and frozen production SPA passed **146 real HTTP checks plus70 native browser API responses** at1280px and390px. Verified actual dropped successful save followed by explicit status GET without repost, even-overall opposing groups, KWD1.234 precision, settled directory retention, bookmark-only removal and retained private label after source-group deletion. Both widths had0 page errors/0 horizontal overflow; screenshots inspected and owned cleanup confirmed. An initial mobile synchronous link-count assertion was replaced with a wait for the rendered links; unchanged source/assets then passed. The owned static gateway also observed an expected canceled-asset socket during hard navigation, with no browser page error.

## Required checks

- Java21: `./mvnw -s /workspace/maven-proxy-settings.xml spotless:apply spotless:check verify` passed347 tests /43 suites, failures/errors/skips0. Final JAR SHA256:eec037c0eb0de798ffecd4080722e2ce8b22ce588870305fd15dd873f549db64. Source manifest203 files matches.
- Node22.23.4: full frontend tests369 /52 files; full-source Prettier and production build passed. Initial bundle552.54kB warns at unchanged500kB, below unchanged1MB error budget. Source163 files and dist42 files match recorded manifests.
- Deployment tests14, ShellCheck and Actionlint passed. Official OpenAPI3.1 schema370 refs/44 paths and81-row CSV/JSON with owner-confirmed USD passed.
- Exact design: [G02.1](../../superpowers/plans/2026-10-26-alignment-g02.md). Public contract: [saved friends](../../api/friends.md).

## Reviews

### Standards

Zero actionable findings. Read-only full diff review at965caa8 against pinned68bca60955a2c11c539a2f880e8275205215f5c1; no further source changes.

### Spec

Zero actionable findings. Separate read-only review at965caa8 against the recorded G02.1 design and serial roadmap scope; no further source changes.

## Migration and rollout

V18 adds an initially empty private directory table with indexed account references; existing financial tables, facts, ownership links, memberships, revisions and receipts are unchanged. Retain the table on rollback to preserve bookmarks. Existing G01 guest-data compatible-forward-fix restrictions continue. No main promotion, release tag, deployment or production SMTP configuration is part of this change. Phone/SMS remains provider-gated. Dependent G02.2 implementation waits for this slice's integration into verified dev.

## PR108 typography review follow-up

Automated review identified component-local typography values despite the shared-token rule. The page-heading font, body line height, caption size and balance size now live in `_tokens.scss`; Friends references these reusable roles with unchanged values. Full frontend369/52, full-source Prettier and production build passed again (552.70kB warning, unchanged budgets). Backend203-file manifest remains unchanged and its347-test result is retained. Final updated-assets native acceptance and independent follow-up reviews are next.
