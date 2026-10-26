# G01 participant identity and invitations candidate

Base: verified integrated `dev` `3fae8e5491717245753573e117b35ff466fbaeab`, after [PR106](https://github.com/dHRvBiyAn1/SplitEasy/pull/106) merged. [Post-merge CI40298485115](https://github.com/dHRvBiyAn1/SplitEasy/actions/runs/40298485115) passed backend/frontend/container checks; deployment skipped. Exact subsystem design was committed as `168377a` before implementation: [G01 plan](../../superpowers/plans/2026-10-26-alignment-g01.md), [identity ADR](../../adr/0001-immutable-financial-participants.md). Public contract: [participants and invitations](../../api/participants-and-invitations.md).

V17 separates stable financial participants from login membership. Existing account UUID mappings and all monetary IDs/values, currencies, split intent, versions, deletion markers and stored revision/retry JSON remain unchanged. Verified explicit claims add immutable ownership links; derived net/direct/simplified obligations combine owned identities before netting and suppress self-debt. Original historical split identities remain available. New defaults deduplicate owners. Membership remove/re-add covers every owned group alias; unclaimed guest removal/re-add preserves its original ID and supports historical expense recovery without reviving revoked invitations.

Email invitations use existing native SMTP, purpose-specific hashed one-use tokens, recipient verification, explicit ownership consent, send deduplication and resend rotation. Group links grant a single expiring/revocable join opportunity using the recipient's own primary identity, never ownership of an existing guest. Acceptance rechecks current JWT/session/account/email state after lock waits. Group/claim/revoke/delete/financial mutations share PostgreSQL group serialization. Guest financial eligibility never grants account access.

`X-Participant-Version: 1` is independent of ledger version 2. Primary-only compatibility is retained, including immediate registered-member addition/re-add. Incompatible scopes fail before new writes; an authorized representable original keyed receipt can still be recovered. No identity is inferred from a UUID or unverified name/phone/email match. Authenticated actors remain accounts, distinct from financial payer/counterparty summaries.

The UI exposes guests, inactive guest repair, invitations/status/resend/revoke and one-time link copying. Invitation fragments are scrubbed immediately, kept only in memory and cleared on session replacement/destruction. Login/verification requires reopening the original invitation. Preview/acceptance is explicit; uncertain outcomes block repeated acceptance and use current owned identities to distinguish a claim from pre-existing membership. Saved drafts keep their original IDs/body/operation, rather than rebuilding retries from a changed projection. H06 foreground refresh, route/session cancellation and meaningful per-currency labels remain intact.

## Verification

Raw evidence: `/workspace/issue-context/alignment-implementation/batch6/`.

| Check | Result |
| --- | --- |
| Backend | Java 21 `./mvnw -s /workspace/maven-proxy-settings.xml -q spotless:check verify`: **335 tests / 42 suites**, zero failures/errors/skips; packaged successfully (`G01/backend-final-verify.log`) |
| Frontend | Node 22 `npm test -- --watch=false`: **356 tests / 51 files** passed (`frontend-readable-table-tests.log`) |
| Formatting/build | Spotless and full-source Prettier passed; production build passed (`frontend-readable-table-prettier.log`, `frontend-readable-table-build.log`) |
| Bundle | Initial 550.04kB versus 500kB warning budget; below unchanged 1MB error budget. No budget increase or new dependency. |
| Repository checks | Deployment-script tests **14 passed**, ShellCheck and Actionlint passed (`deployment-tests.log`, `shellcheck.log`, `actionlint.log`) |
| API | Official OpenAPI 3.1 schema passed; **346 local refs / 42 paths** (`G01/backend-final-openapi.log`) |
| Independent packaged checks | Populated V16→V17 and real loopback SMTP: **169 HTTP responses**, all passed with owned cleanup (`full-http-final.json`) |

Backend JAR SHA256: `94d97d42414ecdba69f8abdd3092921a465a5a385baccbb008aff70ff391b611` (`G01/backend-final.jar`); source/config/test and contract manifests identify the frozen source. The final frontend source/dist manifests and `G01-frontend/frozen-artifact.json` identify the compiled UI.

Sequential HTTP/PostgreSQL regressions caught alias undercount, duplicate owned defaults, stale guest labels, spurious inactive balances, incompatible-client writes, missing viewer mapping, stale-recipient resend capability, a waiting claim surviving logout, and the absent unclaimed-guest re-add/recovery path. Tests preserve primary assertions through authorized HTTP money/history/membership views. SQL only prepares/co-ordinates fixtures and supplies supplemental immutable-JSON digests. Deterministic races cover one-winner claims, both claim/revoke orders and session revocation after authentication while waiting.

Independent final HTTP coverage includes every supported currency and original split mode, raw old JSON digests, immutable original receipts, wrong-account/reused/revoked capabilities, real mailbox verification and pre-signup KWD claims, original self-after-claim payment replay, canonical settlement→leave→re-add→restore, same-ID unclaimed repair and forwarded links with no guest claim. CORS permits the participant capability only from the configured origin. The earlier 50/130-response checkpoint artifacts are retained separately and do not establish final-build acceptance.

Desktop native acceptance passed guest creation, pre-signup KWD expense, actual SMTP, fragment/storage scrubbing, existing-membership-not-claim proof, a dropped actual successful acceptance resolved by owned-identity reads, clipboard copying and forwarded-link join-only behavior. A premature second hard navigation in the harness interrupted bootstrap; waiting for authenticated profile readiness corrected the test without changing auth behavior. A real-initializer regression verifies initial restore preserves the invitation and real device replacement clears it. Native 390px testing then reproduced page overflow; a scoped invitation grid/text/scroll-region fix passed full checks. The final reviewed-format desktop/mobile rerun passed **199 HTTP responses plus 188 browser API responses** at **1280/390**, with zero page errors/page-width overflow and owned cleanup (`native-readable-final/acceptance.json`). Root inspected the mobile claim-review and desktop workflow images. Currency codes remain readable inside a labeled, focusable horizontal table region; the long account email wraps within the page. Final source/assets are captured by `frontend-final-source.sha256` and `frontend-final-dist.sha256`. Independent Standards/Spec reviews and publication/CI remain gates.

## Scope and rollout

Phone/SMS invitations remain gated on a selected verified channel; none is selected. Production SMTP activation is explicitly deferred. Email is tested through an owned loopback sink that never forwards messages. Group links and guests work independently of email delivery. G02 friends/personal ledgers, global notifications/outbox, payment correction and multi-payer work are separate packages.

Drain all old backend readers/writers before enabling V17/current participant traffic: old readers omit guest financial records and old writers omit participant/roster maintenance. After guest/claim data exists, preserve schema/data and use a compatible forward fix. Group deletion retains its existing cascading scope. This candidate does not merge to main, deploy, activate production mail or select an SMS provider.
