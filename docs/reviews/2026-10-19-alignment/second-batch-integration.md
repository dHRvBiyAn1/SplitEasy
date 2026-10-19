# F02 / A01 / E01 integration verification

The user requested implementation of these three packages through reviewed PRs to dev. All branches start at verified dev `337858d8197d9228d80a03dfcc7d3aef654b4e04`. This report records a disposable local merge preview; it does not merge a remote PR or promote a release.

## Sources and merge compatibility

Combined preview `28c9f163f78c77c802c590d2fc6681614639f887` includes F02 application `fcfd5fd`, A01 final application `3ed7d1c` and E01 application `4dcfa1e`. Later branch commits retain evidence only. The implementation sources merge automatically, including both sets of session-isolation tests. Agent-memory conflicts preserve the canonical departure rule, server-owned profile rule, both migration data models and all package progress entries.

A01 V10 must precede E01 V11. Promote both together or V10 first; do not apply V11 then introduce a lower unapplied V10. Migrations V1–V9 are unchanged. V11's nullable original inputs support old writers, but old binaries cannot read WEIGHTS/ADJUSTMENTS rows after those modes are used. At that point use a forward fix or a reviewed data conversion/restore; do not promise an unrestricted binary rollback.

## Verification

- JDK21/PostgreSQL16: `./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` passed **255 tests**, zero failures/errors/skips, with runnable JAR packaging.
- Node22.23.3: full Angular tests passed **236 tests /32 files**. Full-source Prettier and production Angular build passed.
- Official OpenAPI3.1 schema, duplicate-key rejection and **144 internal references** passed. The81-row feature backlog and38-package dependency graph remain intact.
- Real packaged-JAR rehearsal booted the known V9 baseline, populated users and a tiny percentage expense, then upgraded that same owned database through **V10 then V11**. Original financial shares and identity/registration data survived; legacy unknown percentages became explicit exact amounts.
- **70 HTTP requests /four scenarios** passed: migration preservation; account persistence/ownership/atomic validation; original percentages, amount changes, weights, signed adjustments and rejection; canonical obligations, both modes, departure and historical repair guards.
- Production Angular/System Chromium ran all three packages at1280px and390px against that combined JAR: **185 real browser API requests /six flows**, no fixture responses, no page errors or horizontal overflow. Profile save/fresh browser/account switch/explicit import, original percentage editing/new split modes, canonical settlement/leave/rejected former-member exposure all passed. Only API URL and allowed local Origin were adapted to the owned server.
- Owned Java application, PostgreSQL container/volume and static server were removed after rehearsal. No production data or API was used.

Workspace-only orchestration/evidence is retained under `/workspace/issue-context/alignment-implementation/batch2/`: `rehearse.py`, `browser_probe.py`, `integrated-tests.json`, `combined-http-browser-green.json` and per-package logs/screenshots. The repository's retained HTTP/rendered regressions and required CI remain the executable checks in fresh checkouts; these workspace artifacts are additional rehearsal evidence.

## Independent review

Each package received separate Matt Pocock Standards and Spec reviews. E01 and F02 had zero findings. A01's initial Spec P2 found a read-only email display using stale auth data; its red→green fix now renders accepted profile email in both modes while forbidding email mutation. Both final A01 axes report zero remaining findings. A proposed import-cache issue did not reproduce under a retained same-singleton/component-recreation regression, so no speculative service change was made.

PR CI must still pass at each exact final branch head. This integration verifies compatibility; it does not claim that these unmerged changes are deployed, that future currency/session/version packages shipped, or that the full alignment roadmap is complete.
