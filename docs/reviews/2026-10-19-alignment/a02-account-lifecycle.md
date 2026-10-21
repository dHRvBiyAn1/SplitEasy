# A02 backend account lifecycle evidence — 21 October 2026

Base: `e5b219773e54f890e56358f2a57de28f56d4bdff` (`dev`). Execution design:
[2026-10-19-alignment-a02.md](../../superpowers/plans/2026-10-19-alignment-a02.md).
Contract/operator limits: [account recovery](../../security/account-recovery.md).
This records the backend checkpoint; frontend, independent review, packaged
SMTP/populated-upgrade/browser rehearsal and final PR gates are coordinated by root.

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
