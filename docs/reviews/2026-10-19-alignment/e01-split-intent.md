# E01 original split inputs, weights and adjustments

Base: `337858d8197d9228d80a03dfcc7d3aef654b4e04` (verified dev). Scope: approved roadmap E01 and design section5, with user-approved HTTP/PostgreSQL, rendered Angular/HTTP and owned browser seams. Matt Pocock TDD and Ponytail; no internal collaborator mocks. This is implementation evidence; independent Standards/Spec reviews remain the parent workflow's gate. No push, PR, merge or deployment by the implementer.

## Behavior and contract

An expense retains final `participants[].shareCents` plus original `splitType` and either `participantUserIds` (EQUAL) or `splits[{userId,value}]` (custom modes). Full-replace PUT uses those original inputs. Explicit zero inputs and zero final shares remain participants; blank UI inputs exclude members. Editing never adds other group members. The one-cent 50/50 percentage keeps5000/5000 basis points through GET/description edit and recomputes50/50 cents at a100-cent total.

WEIGHTS accepts positive integer units1..1e12. Each allocation is floor(total×weight/sum(weights)); the payer absorbs remaining cents, otherwise the first participant in the existing Java UUID signed high/low order. Arithmetic uses `BigInteger`, including multiplication and sums. The editor retains whole units on amount changes.

ADJUSTMENTS accepts signed cents within±1e12. The listed split users define the participant set. Calculate the existing deterministic EQUAL allocation across that set, then add each user's signed adjustment. Adjustments must sum0, and every final share must be nonnegative (therefore no greater than total). The editor validates its cached, normalized signed values against the same allocation, including the Java UUID order when the payer is excluded. A smaller amount that would make a share negative visibly blocks submission. Existing write serialization, account/group ownership, access checks and inactive-history409 remain intact.

## Migration and compatibility

V11 adds nullable `expense_participants.split_value` alongside final `share_cents`. EQUAL stores1; custom types retain original typed values. Existing EQUAL participant sets are recoverable, including zero shares. Existing non-equal intent was discarded by old code: V11 backfills exact final shares and converts legacy PERCENTAGE to UNEQUAL. It never guesses percentages or weights.

The nullable column allows older binaries to insert during rollout. A non-equal expense with any missing original value is returned as UNEQUAL exact shares, including a PERCENTAGE written by old code after migration. Old code replacing a known percentage can lose intent; the fallback is safe exact editing. This supports schema compatibility, not unlimited mixed-version feature compatibility.

**Rollout requires A01 V10 before E01 V11**, either both in the same batch release or V10 deployed first. V10 is reserved for A01; this branch intentionally rehearses V9→V11 with that gap. Do not deploy V11 first and expect V10 to run later under normal Flyway ordering. V1–V9 are unchanged.

After WEIGHTS/ADJUSTMENTS rows exist, an older binary cannot read those enum values. Downgrading requires a forward fix or a reviewed exact-mode conversion/backup restore; ordinary unrestricted binary rollback is not safe. Conversion would discard the new original intent. No contribution, currency, retry identity, optimistic-version or ledger projection changes are included.

## Regression evidence

Evidence directory: `/workspace/issue-context/alignment-implementation/batch2/E01/`.

| Slice | Before | After |
| --- | --- | --- |
| Tiny original percentage via HTTP | `01-percentage-red.log`: missing original `splits` | `01-percentage-green.log`:1 pass |
| Original percentage in rendered editor | `02-editor-red.log`: selected UNEQUAL | `02-editor-green.log`:48 pass |
| Positive integer weights via HTTP | `03-weights-red.log`: new mode400 | `03-weights-green.log`:2 pass |
| Zero input retained on edit | `04-zero-editor-red.log`: dropped zero participant | `04-zero-editor-green.log`:49 pass |
| Whole units in rendered editor | `05-weights-editor-red.log`: missing Weights control | `05-weights-editor-green.log`:50 pass |
| Signed equal adjustments via HTTP | `06-adjustments-red.log`: new mode400 | `06-adjustments-green.log`:3 pass |
| Signed adjustment edit/amount validation | `07-adjustments-editor-red.log`: missing selected control | `07-adjustments-editor-green.log`:51 pass |

Expanded HTTP coverage (`08-http-boundaries.log`):11 passing tests, including atomic invalid POST/PUT rejection with unchanged GET detail/list/balances, cap-scale weight multiplication, one-cent payer-excluded remainders, old-writer exact fallback, original equal/exact inputs, zero-share inactive history409 and outsiders403 before404. SQL only creates the old-writer fixture; assertions use public reads. Expanded rendered coverage (`09-editor-boundaries.log`):57 pass, including invalid whole weights, restored units, negative adjustments, amount changes, original zero inputs, excluded-payer UUID ordering and actual serialized payloads.

## Verification

- Backend: `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1 PATH=/workspace/toolchains/jdk-21.0.12.1+1/bin:$PATH ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` (from backend):241/241 pass; Spotless clean; packaged executable JAR produced (`backend-verify.log`).
- Frontend: `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false`:230/230 pass across31 files.
- Production build: `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build --configuration production`:pass.
- Full formatting: `npm exec --yes --package=node@22 -- node node_modules/prettier/bin/prettier.cjs --check 'src/**/*.{ts,html,scss,json}'`:pass.
- OpenAPI: duplicate YAML keys rejected; all135 internal refs resolve; five modes and original fields checked. Full OpenAPI validator is unavailable in this workspace; no full-schema validation claim.
- Owned Chromium: `python3 /workspace/issue-context/alignment-implementation/batch2/E01/browser.py`:pass at1280px and320px. The production Angular bundle accepts native Enter activation of Weights/Adjustments, rejects fractional units, permits signed native form inputs, posts units/signed cents, retains5000/5000 for a one-cent description edit and100-cent amount edit, and has no page errors/horizontal overflow. Outputs: `browser.log`, `browser-{1280,320}.json`, `weights-{1280,320}.png`. An initial harness attempt needed to re-expand the row after successful mutation refreshed/collapsed it; no application change was needed. Browser responses are controlled; HTTP/Testcontainers supplies real Spring Security/PostgreSQL behavior. No production or physical-device/screen-reader claim.
- Populated migration rehearsal: parent verification pending against the packaged JAR; standalone V9→V11 gap and final combined V9→V10→V11 are separate checks.
