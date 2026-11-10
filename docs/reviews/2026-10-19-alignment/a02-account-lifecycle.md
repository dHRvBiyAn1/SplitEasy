# A02 account lifecycle evidence — 21 October 2026

Base: `e5b219773e54f890e56358f2a57de28f56d4bdff` (`dev`). Execution design:
[2026-10-19-alignment-a02.md](../../superpowers/plans/2026-10-19-alignment-a02.md).
Contract/operator limits: [account recovery](../../security/account-recovery.md).
Backend checkpoint evidence is followed below by the frontend and root review
results. The final batch integration report records the combined acceptance gate.

## Result and scope

Public mailbox confirmation supports forgotten passwords, explicit email ownership
verification and pending replacement email. Authenticated password/email changes
require the current password. Challenges use only purpose-bound SHA-256 hashes,
30-minute expiry and transactional single use. User-row locks serialize issuance,
consumption and credential changes; pending email never reserves an address and
PostgreSQL arbitrates final uniqueness. Registration/upgrades leave email unverified.

Password reset/change and confirmed actual email change increment persisted
credentialVersion. Every protected request validates it; missing legacy claims
mean zero until credentials change. Email verification does not revoke JWTs.
This is a bounded credential boundary; device sessions, refresh and logout-all
remain A03. A request already authenticated at commit may finish.

Native SMTP was explicitly selected. No production sender, credential, provider
account, real delivery or runtime configuration was added. Delivery defaults off.
Remote SMTP requires a configured host, effective `smtp` protocol, trusted HTTPS
frontend origin, authenticated TLS, hostname checks and finite positive timeouts.
Only the explicit owned-loopback test exception permits plaintext. Public actual
transport failures roll back issuance while preserving generic202 for known and
unknown accounts; global disabled/misconfigured transport is uniform503.

Production Compose retains no published ports. `mail_egress` attaches only the
backend and defaults internal; enabling native SMTP also requires an explicit
`APP_MAIL_EGRESS_INTERNAL=false`. App/database networks stay internal.

## Public-seam verification

Tests use real MVC/security filters and disposable PostgreSQL, with a controlled
standard JavaMailSender boundary. Database access only builds expiry fixtures or
coordinates lock ordering; primary outcomes are HTTP/login/profile/mail outcomes.
External logs are retained under `batch3/A02/` in the implementation context.

- `01`–`05`: disabled uniform503, mailbox reset and credential revocation, verified
  pending replacement, authenticated password change and BCrypt byte boundaries
  each observed red then green. The byte case exposed an existing login-prefix
  acceptance bug: login now rejects >72 UTF-8 bytes before BCrypt with generic401.
- `06`–`10`: actual-send failure genericity/rollback; replacement, expiry, wrong
  purpose, replay and concurrent consumption; simultaneous replacement-email claims
  have one204/one409 and preserve the loser; a reset queued behind email confirmation
  cannot recover from the former mailbox. The latter required selecting scalar UUID
  before the row lock, avoiding a preloaded stale managed email.
- `11`, `12`, `15`: remote hostname checks, finite timeouts and effective protocol/host
  configuration observed red then green. Effective protocol follows JavaMail's
  explicit protocol → session property → `smtp` resolution, preserving its null default.
- `13`: six JWT filter tests pass, including invalid algorithm/issuer/signature/expiry,
  malformed/negative/null/mismatched versions and unknown users; legacy tokens work
  at version0 and fail after credential change, while fresh login works.
- `16`: lifecycle IP/account/confirmation/sensitive-command budgets return429 with
  Retry-After; unknown addresses count, normalized account caps span IPs, and existing
  protected access/login remain usable. The guard's null-default regression was caught
  and corrected before the final combined check. The combined targeted run passes4.
- `14`: six Compose configuration tests pass, including explicit egress opt-in,
  backend-only attachment, no published ports, forced production TLS/hostname/local
  settings and rejection of an invalid network boolean.

Final backend verification (JDK21 `JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1`):

- `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify`
  passes **272 tests, zero failures/errors/skips**, produces the Spring Boot JAR;
  Spotless keeps137 Java files clean. Log: `18-backend-verify.log`.
- `python3 -m unittest discover -s deploy/tests -p 'test_stack.py'` passes **6 tests**.
- Duplicate-key YAML parsing, **166 internal references**, and the cached official
  OpenAPI3.1 schema pass (`19-openapi-validation.log`).
- `git diff --check` passes. V13 is the only migration addition; older migration
  files are unchanged.

A stable copy outside Maven's target is retained as
`batch3/A02/artifacts/backend-a02-verified.jar`, SHA-256
`febcad943bd067435357cf347e4b1ded27bd04a08987ed973f8f6f7f4d51cf49`.
Root can rehearse that exact backend while frontend work continues. No frontend
verification or real SMTP/inbox-delivery result is claimed in this checkpoint.

## Migration and rollback

V13 alone adds unverified/default-zero credential columns and the challenge table.
V1–V11 are immutable. E02 V12 must precede V13 in promotion, or both ship together;
A02's isolated fixture can apply V13 against V11 but that does not authorize the
production order. Root owns the populated-V11/package rehearsal.

Retain V13 on binary rollback. Old auth binaries ignore credentialVersion and
therefore accept JWTs revoked by A02; an old-binary rollback is unsafe after
credential changes without a reviewed all-instance signing-key rotation and forced
re-login. Prefer a forward fix. SMTP acceptance does not prove inbox delivery,
there is no durable outbox/retry, and process-local abuse budgets must become shared
before multiple API instances. No merge, push, production promotion or deploy was
performed by this worker.

## Frontend and review remediation

Public forgot-password preserves delivery-neutral messaging and retryable drafts.
Mailbox reset/verification/replacement pages remove URL fragments synchronously,
retain tokens only in component memory and require explicit submit. Profile
security uses server verification state and current-password proof; pending email
keeps the old login until new-mailbox confirmation. Success/session/destruction
clear secrets. Existing AuthService/ProfileService own concrete HTTP commands;
components retain UI/session cancellation. Invalid submits focus the sole field or
a linked multi-field summary. No additional session framework/dependency exists.

AuthService caches a coherent token/user pair, compares JWT subject only for
presentation coherence, and resets owned work on cross-tab credential changes.
API signature/issuer/expiry/credential-version validation stays authoritative.
Partial pairs clear local state without erasing newer shared credentials; stale
logout/profile callbacks cannot erase an unseen newer login. A public A mailbox
action does not blindly log out or mark a currently signed-in B account verified.

Independent backend Standards and Spec reviews each found zero material issues.
Final UI review found two documented Standards breaches (resource ownership and
invalid-submit focus) and one Spec P2 (cross-tab replacement cleared reads but did
not initialize B). Rendered red→green regressions now cover all three corrections;
separate remediation reviews report zero remaining hard/material findings. A small
fixed-action presentation switch smell was retained as a nonblocking judgement
call rather than introducing a dispatch framework.

Real browser rehearsal found that conditional shell/public router outlets
recreated the fragment-cleared mailbox component for signed-in users. A rendered
regression reproduced the missing confirmation button; one stable router outlet
preserves the explicit in-memory form across shell changes. The shell effect now
tracks the existing session generation so A→B refreshes B even when authenticated
visibility stays true. Integrated navigation/focus/coalescing and write ownership
remain unchanged. Same-route mailbox re-entry receives an additional native
hashchange regression and real browser replay in the final integration report.

Backend-only root rehearsal already passed35 real HTTP responses using the native
SMTP loopback sink, including reset, verification, pending/confirmed email,
password change, token replay/purpose, revocation and legacy-token behavior.
Tokens were absent from server logs. The populated combined upgrade preserves
identity/profile/money/split intent through V12 then V13. All resources are owned
and disposable; SMTP acceptance in this sink is not production inbox delivery.


### Final router and UI checks

Actual BrowserPlatformLocation plus Angular Router reproduced restoration of a
mailbox fragment after the native hash handler had removed it. NavigationEnd now
scrubs the URL again without reparsing or clearing the explicit form, password or
pending action. The real same-tab browser scenario remains unchanged; this is a
source fix rather than a weakened rehearsal. Rendered red→green covers the real
Router/location boundary, in addition to native re-entry/session cancellation.

At `ea09931a93b4b82a7d336a3702662ef62af79a52`, standalone full Node22 frontend
**261 tests /35 files**, full-source Prettier and production build pass (no warning
in standalone A02). Final separate Standards and Spec delta reviews report zero
remaining hard/material findings. Full combined preview `d9534486` passes
**287 tests /37 files**, formatting and production build; its501.19kB initial bundle
has a non-fatal1.19kB warning above the500kB warning budget. Backend source remains
identical to the combined280-test verification at `dd087d4`; no backend rerun is
claimed for UI-only changes. External UI logs39–43 retain the final actual-Router
red/green and full checks. The following integration report records final real
mailbox/browser outcomes and the migration/rollback gates.


## Final owned browser acceptance

Final combined preview `53820d6` passes all three packages at1280/390 using the
real JAR/Pg and non-forwarding local SMTP sink:59 bootstrap/migration/HTTP responses
plus all six package/viewport flows. A02 observes58 responses and all seven account
stages per viewport, including same-tab link reopening and preservation of an
unrelated B session. No page errors or horizontal overflow. The mobile long-email
status correction is two scoped CSS properties; source/rendered behavior is
otherwise unchanged and the final combined287 tests pass. The owned app/Pg and
other test resources were cleaned up. Full evidence and limits:
[third-batch integration](third-batch-integration.md) and
[separate review axes](third-batch-independent-review.md). PR CI remains an
independent exact-head gate; no merge or production delivery/deployment is claimed.

## PR #103 P2 — claimed replacement address (23 October 2026)

Review comment4338584096 identified the documented email-change confirmation409
falling through to temporary-failure feedback. On baseline
`f6b9ae8554f808dbaf473c846c80aac3277f5322`, one rendered Angular/controlled-HTTP
regression reproduced “Temporarily unavailable” for an already registered target
(1 failed/12 passed). The minimal fix handles409 only for email change, clears the
in-memory challenge and removes its confirmation form. It explains that the
address is already registered and directs a new email-change request with a
different address through the existing Account security/profile link.429/503
retry and400 invalid/expired handling remain unchanged.

The same regression passes (13/13) and verifies explicit confirmation, fragment
cleanup, unavailable form retries, no automatic follow-up POST/profile refresh,
and preservation of an unrelated signed-in user's identity, token and storage.
No API/backend, sender, migration, dependency or deployment change was needed.
Full verification from `frontend/` on Node22:

- `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js test --watch=false`
  —262 tests/35 files pass.
- `npm exec --yes --package=node@22 -- node node_modules/prettier/bin/prettier.cjs --check 'src/**/*.{ts,html,scss,json}'`
  —all matched files pass.
- `npm exec --yes --package=node@22 -- node node_modules/@angular/cli/bin/ng.js build --configuration production`
  —passes,492.42kB initial bundle, no budget warning.

Red/green runs use the test command with
`--include=src/app/auth/account-recovery.spec.ts`. Logs:
`/workspace/issue-context/alignment-implementation/batch3/A02/UI/pr103-p2-{red,green,full,prettier,build}.log`.
No new backend or real-browser execution is claimed for this narrow UI correction;
existing package integration evidence above remains separate. Root owns remote
push, review reply/resolution, exact-head CI and integration; no merge or release.
