# Verified identity and account recovery

A02 uses native SMTP via Spring Boot's standard mail starter. No email provider,
production sender, credential or delivery service is bundled or implicitly enabled.

## Sender configuration

Delivery is disabled by default. Enable it only with a sender you control:

- `APP_MAIL_ENABLED=true`
- `APP_MAIL_FROM` — one valid sender address, authorized by your SMTP server
- `APP_MAIL_FRONTEND_URL` — trusted HTTPS frontend origin (no user info/query/fragment)
- `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`
- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true`
- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_CHECKSERVERIDENTITY=true`
- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`
- `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true`
  (or `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true` for implicit TLS;
  retain the standard `smtp` protocol so the same validated properties apply)

Production Compose forwards the optional settings with delivery disabled by default,
uses `PUBLIC_ORIGIN` for links, forces the local plaintext exception off, and
requires authenticated TLS with SMTP hostname verification. The backend's dedicated
`mail_egress` network is internal by default; native SMTP deployment must explicitly
set `APP_MAIL_EGRESS_INTERNAL=false` as well as `APP_MAIL_ENABLED=true`. This enables
backend outbound networking without publishing ports or adding Caddy, cloudflared
or PostgreSQL to that network. Existing app/database networks remain internal.
No runtime sender or network configuration has been changed by this package.

Standard connection/read/write timeouts are 5 seconds; remote SMTP configuration
is rejected if any timeout is absent, nonnumeric, zero or negative, or hostname
verification is disabled. Keep SMTP/session debug off;
it can print message content. Never capture production mail, tokens, passwords or
SMTP credentials in logs. SMTP acceptance is not proof of final inbox delivery.
No durable outbox/retry is promised; users can deliberately request a new link.

The only plaintext exception is an explicitly owned local test mailbox:
`APP_MAIL_ALLOW_INSECURE_LOCAL=true`, a loopback SMTP host and loopback frontend
origin. This switch never permits remote SMTP or a remote HTTP frontend. It is
not a production delivery mode. Do not enable it in production configuration.
The package tests control the standard JavaMailSender seam; the root's packaged
rehearsal uses an actual loopback SMTP capture that never forwards mail.

Disabled/misconfigured delivery returns sanitized 503 uniformly before account
lookup. Actual transport failure rolls back issuance and still returns generic
202 for public reset requests, preserving known/unknown account privacy; 202
promises no delivery. The UI says a link may arrive and explains retry/support.
Authenticated mail requests report transport failure as sanitized 503. No capture
URL, raw token API or log fallback exists.

## Identity and challenge contract

Login/registration remain possible before email verification. Existing users are
not marked verified by migration. GET `/api/me` exposes `emailVerified`.

- POST `/api/auth/password-reset-requests` `{email}` returns generic 202 when
  configured. Unknown and known accounts have the same response and no token.
- POST `/api/auth/password-resets` `{token,password}` resets a password, 204.
- POST `/api/me/email-verification-requests` `{}` sends to current persisted email,
  202; POST `/api/auth/email-verifications` `{token}` confirms it, 204.
- POST `/api/me/password` `{currentPassword,password}` changes a password, 204.
- POST `/api/me/email-change-requests` `{currentPassword,email}` sends to a pending
  normalized replacement, 202. Current password plus confirmation of the new
  address is sufficient; access to a lost old mailbox is not required.
- POST `/api/auth/email-change-confirmations` `{token}` changes the login address
  and marks it verified atomically, 204. The old address remains the login before
  confirmation. An address already claimed by another account returns 409;
  PostgreSQL arbitrates concurrent claims. A pending request never reserves it.

Links use `/account-action#action=...&token=...`. The frontend removes the fragment
from the current browser history entry and holds it only in memory. Opening a
link never consumes it: the user submits an explicit form. Email scanners cannot
consume tokens by GET. Reloading after removal requires reopening the mailbox
link. Do not add third-party analytics/scripts that capture fragments.

Challenges contain 32 cryptographically random bytes, encoded URL-safe without
padding. Only SHA-256 hashes are stored, alongside purpose/user/target/30-minute
expiry. Each user has at most one outstanding challenge per purpose; reissue
invalidates the prior link. Expired, replayed, replaced, malformed and wrong-purpose
links return generic 400. A locked user row and transactional recheck serialize
consumption with issuance/credential writes. Credential changes invalidate all
outstanding challenges, preventing a pre-change reset/email-change from undoing
a later accepted change. Email verification checks its persisted target address.
Passwords require 8 characters and no more than 72 UTF-8 bytes (BCrypt); all
current-password checks are bounded before BCrypt.

Password reset/change and confirmed actual email change increment persisted
credentialVersion in the same transaction. Every protected request validates the
JWT claim against that version; absent legacy claims mean zero. All older tokens
are rejected after commit. A request authenticated before commit may finish.
Device sessions, refresh tokens, selective session revoke and logout-all UI remain
A03 work. Client logout alone still does not revoke copied tokens.

## Abuse limits and rollout

Recovery and confirmation have bounded process-local IP/account windows and
429/Retry-After. They reset on restart and must become shared before multiple API
instances. These controls preserve the existing login/registration policy.

V13 is additive: users gain unverified/default-zero credential state, and a
purpose-constrained challenge table. Deploy E02 V12 before V13 (or both together).
Keep V13 applied on binary rollback; dropping it discards lifecycle state. An old
binary does not validate credential versions, so rolling back auth code restores
old-token acceptance and is not a safe security rollback after credential changes.
Use a forward fix, or rotate signing keys and force re-login on every API instance
before any reviewed old-binary rollback. No production SMTP/delivery verification
has been performed by the A02 worker.
