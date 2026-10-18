# Authentication attempt limits

The application adds a small, independent protection for its single Java instance. The user authorized these app limits because existing Cloudflare account rules are unknown; this does not claim the live edge has no protection.

| Endpoint | Scope | Allowance | Window |
| --- | --- | --- | --- |
| POST `/api/auth/login` | IP | 30 attempts | 5 minutes |
| POST `/api/auth/login` | normalized email + IP | 5 attempts | 5 minutes |
| POST `/api/auth/register` | IP | 20 attempts | 1 hour |

The registration allowance supports shared-IP group onboarding and can be changed with `APP_AUTH_REGISTRATION_LIMIT` (positive integer). Login scopes apply together. An account's budget is local to each IP, avoiding an account-wide lockout an unrelated remote visitor could trigger. Distributed guessing across many source IPs remains an edge/monitoring concern.

Every validated attempt counts before account lookup or BCrypt, including successful login/registration and failed credentials or email conflicts. Success does not clear the budget. Malformed/invalid DTOs retain their existing 400 behavior without reaching credential processing. Email keys use the same trim/lowercase normalization as authentication, and email inputs are bounded to 254 characters. Registration needs no account bucket: its IP cap also limits email rotation and repeated conflicts. Login and registration budgets are independent; existing tokens, protected routes and health requests do not consume them.

Windows begin on the first attempt and expire after their fixed duration using monotonic time. A rejected request neither increases the counter nor extends its deadline. Exhaustion returns generic JSON status 429 and an integer `Retry-After` rounded up to seconds (1–300 for ordinary login limits, 1–3600 for registration). CORS exposes that header to the production frontend. Both forms show “Too many attempts. Please wait before trying again.” and allow a later manual retry; there is no automatic retry loop, identity change or logout on 429.

## Client IP trust boundary

Production is `Cloudflare → cloudflared → private Caddy → Java`. `compose.prod.yml` publishes no application ports; cloudflared shares only the edge network with Caddy, and only Caddy/Java share the app network. Cloudflare supplies the visitor's `CF-Connecting-IP`; Caddy's native `trusted_proxies static private_ranges` and `client_ip_headers CF-Connecting-IP` accept that metadata from the private tunnel peer. Caddy replaces downstream `X-Forwarded-For` with its resolved `client_ip` and removes `CF-Connecting-IP` and `Forwarded`. Caller-supplied XFF is never selected as the visitor identity. Native Tomcat remote-IP processing is enabled only in the production profile and accepts the private Caddy peer; the controller uses `request.getRemoteAddr()` and never parses forwarding headers.

Local direct development uses `server.forward-headers-strategy=none` and ignores arbitrary forwarding headers. The disposable CI override exposes Caddy only on loopback; private local callers can simulate trusted tunnel metadata for tests. Do not publish Caddy/Java ports or attach untrusted containers to these private networks: that would invalidate this trust boundary. If the topology changes, narrow/reconfigure native proxy trust before exposure. Missing tunnel metadata falls back to the proxy peer and can share a budget, so retain the Cloudflare header on the tunnel.

## Memory, restarts and operations

One synchronized in-memory map atomically checks and reserves both login scopes, including concurrent requests. It retains at most 10,000 bounded-size buckets across endpoints. At capacity it removes expired entries; active counters are never evicted to admit new identities. If capacity is still exhausted, new buckets receive 429 with a bounded retry until the earliest active expiry (up to one hour). Existing buckets keep their allowances. This fails closed under saturation, so an identity flood can temporarily deny new authentication attempts; already authenticated API usage remains available.

Counters reset on process restart and are independent across instances. Keep the current single-instance deployment; introduce shared enforcement or an explicitly verified edge policy before horizontal scaling. Fixed windows also permit a burst spanning a window boundary. No new datastore, limiter dependency or schema migration is needed for the current deployment.

The limiter adds no password, token, email or IP logging. It does not emit one warning per rejected attempt, avoiding an abuse-driven log flood. Existing successful authentication events and generic bad-credential logs remain unchanged. Metrics/alerting and broader account-enumeration policy are separate work: login errors stay generic; registration's existing 409 for duplicate email remains unchanged.
