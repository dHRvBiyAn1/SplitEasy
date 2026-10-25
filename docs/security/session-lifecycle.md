# Authentication and session lifecycle

Login and registration create a persisted device session and return the existing
HS256 bearer response (`expiresIn=3600`) plus an opaque `sessionId`. Every access
JWT includes `sid` and the account's `credentialVersion`. Protected requests
validate signature, HS256, issuer, timestamps, current account version, and a
matching, active, unexpired persisted session owned by the JWT subject. Cookie
possession alone never authenticates a business request.

## Cutover and immediate revocation

A03 rejects all JWTs without a valid persisted `sid`, including previously issued
legacy JWTs. Users sign in once after rollout. There is no legacy-token adoption
endpoint or grace period that leaves copied tokens outside device revocation.
The existing missing-credentialVersion-as-zero parser remains, but never exempts
a token from the session check. JWT timestamps retain Spring's 60-second skew;
persisted session expiry and revocation are independent server checks.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/me/sessions` | List only the caller's active devices, dates and current flag |
| DELETE | `/api/me/sessions/{id}` | Revoke one owned device; unknown/foreign IDs also return 204 |
| POST | `/api/auth/logout` | Revoke the session in the captured bearer token |
| POST | `/api/me/sessions/logout-all` | Revoke every session of the authenticated account |
| POST | `/api/auth/refresh` | Rotate the browser refresh credential and issue one-hour access |

Logout requires bearer authentication even though other public commands use the
`/auth` prefix. Revoke and logout-all serialize on the existing account row lock,
shared with refresh, login and credential changes. After commit, later protected
requests on every API instance reject copied access tokens. Requests already
being authenticated or running when revocation commits may finish; there is no
attempt to cancel completed business work.

Password reset, authenticated password change and confirmed actual email change
atomically increment credentialVersion, revoke all persisted sessions and
invalidate mailbox challenges. Verification alone does not revoke sessions. Login
selects a scalar account UUID, locks, and rechecks both normalized email and
password after waiting, including an overlapping confirmed email change.

## Refresh credential and browser protection

Refresh credentials contain256 random bits and are returned only in the
host-only `__Host-spliteasy-refresh` cookie: Secure, HttpOnly, Path=/,
SameSite=Strict, no Domain. Only SHA-256 hashes are stored in PostgreSQL. Access
credentials stay in JavaScript memory; neither access nor refresh credentials
are written to browser storage, URLs or application logs. Legacy localStorage
bearer/user keys are discarded. Mailbox-link handling remains governed by
[account recovery](account-recovery.md).

The production frontend (`https://split-easy.io`) and API
(`https://api.split-easy.io`) are same-site and cross-origin. Browser
login/register/refresh requests use credentials. CORS permits exactly configured
origins and credentials, retaining existing permitted headers, including
X-Ledger-Version. Refresh additionally requires an exactly trusted Origin and
`X-SplitEasy-Session: 1`; missing/null/untrusted origins or a missing header return 403
before token lookup. Login/register reject a supplied untrusted Origin, preventing
login CSRF. Native non-browser login/register can omit Origin. Other bearer-only
commands do not gain ambient cookie authentication.

Secure is never disabled for development. Browsers support localhost as a secure
cookie/Web Locks context; remote development must use HTTPS and an explicitly
allowed frontend Origin. Previews are not implicitly trusted. Responses issuing
credentials are no-store. Neither logout nor failed refresh clears a cookie:
a delayed old response must never erase a newer login's cookie. A revoked cookie
cannot renew and a later login replaces it.

## Rotation, races and server bounds

Each session has a 30-day absolute expiry, a 7-day idle expiry renewed only on
refresh, and a budget of 2048 successful rotations. The next renewal after budget
exhaustion revokes the family and requires sign-in. A cookie lasts at most7 days,
clipped to the remaining absolute lifetime; persisted timestamps always win.
Access keeps its 3600-second nominal lifetime, bounded by session revocation and
expiry. Ordinary business reads do not write session activity timestamps.

The account lock serializes credential consumption and replacement. Every
consumed hash is retained for the family so reuse of any older credential revokes
the whole session, including access and refresh credentials issued to an earlier
winner. Reuse returns401 after committing that revocation. There is no replay
grace or restoration of consumed credentials. Concurrent duplicate renewals
therefore produce one 200 and one 401, and the winner's credentials subsequently
fail. A lost refresh response requires sign-in; the browser does not automatically
retry the refresh request.

Login deletes revoked, expired and obsolete-version families and their hashes,
then removes the oldest devices before inserting a new one, retaining at most 20
families per account. Each family has at most 2049 hashes. Cleanup is account-local
on login: inactive accounts can retain bounded expired rows until their next
login. Deleted/unknown old credentials still return 401. There is no scheduler or
new datastore/dependency.

Refresh has an independent 120/IP/5-minute process-local budget in the existing
bounded limiter. Login, registration and recovery limits are unchanged; see
[authentication abuse limits](auth-abuse.md). Persisted revocation works across
instances, but attempt budgets must be shared before horizontal scaling.

## Client continuity and ownership

Initial cookie renewal finishes before routing. Eager pending-write cleanup is
installed before renewal. Initial restoration does not reset a valid owned
retry draft; reading a draft still requires its immutable stored owner to match
the successfully restored account. Reload never submits a financial write.
Later account/device replacements and every logout reset account state, cancel
owned callbacks and clear pending writes. Same-device access rotation preserves
the session generation and form drafts, including profile edits.

A protected 401 shares one renewal with concurrent failures and can retry at most
once. It captures immutable user ID, session ID, session generation and the
authentication attempt, and checks all four after renewal. Starting explicit
login/registration supersedes old callbacks before its response replaces the
identity; an older renewal cannot clear or invalidate that newer sign-in.
A different account or device cancels the old request instead of replaying it
under the new identity. Late failures never issue a server logout against a
replacement account.

Native Web Locks serialize login/register/refresh across tabs through the entire
HTTP response and cookie/body processing. Other tabs receive only a nonsecret
localStorage change marker; they clear old owned state and restore the identity
from the HttpOnly cookie. Successful renewals do not broadcast another marker,
avoiding renewal loops. Old-marker callbacks cannot overwrite newer state.
Without Web Locks a duplicate refresh fails closed and may require sign-in.
Cross-tab notifications and persisted local sign-out intent require browser
storage; credentials are never used as a fallback storage mechanism.

Local logout clears memory immediately and sends captured bearer credentials.
If the request fails, the login screen says server sign-out could not be
confirmed and copied credentials/other devices may still work. A nonsecret
signed-out marker prevents a reload from silently restoring that unconfirmed
cookie until an explicit successful login. It is not server revocation. An old
tab preserves a newer unseen login marker when signing out its own bearer.

Profile's Manage sessions panel loads on demand, identifies the current device,
shows created/last-renewed/maximum-expiry dates, supports selective revoke and
Sign out all devices, and offers manual retry after errors. Device labels are
coarse browser/platform names; the API exposes neither IP addresses, raw user
agents nor any refresh hashes or credentials.

## Migration, release and rollback

A03 owns V14; C01 owns V15+. Apply V14 before V15 or ship them together. Do not
use Flyway out-of-order mode. Stop/drain old backends before serving the session
contract: mixed old/new binaries cannot guarantee immediate revocation. Coordinate
frontend/backend rollout and the forced one-time sign-in.

V14 is additive. Keep its schema on rollback. Old authentication binaries accept
session-revoked access JWTs, so prefer a forward fix. A reviewed binary rollback
requires an all-instance signing-key rotation, forced sign-in and a compatible
frontend; it restores the older authentication behavior rather than preserving
A03's guarantees. Production SMTP/provider setup remains outside this change.

Verification includes authenticated MVC with real PostgreSQL, consumed-token and
concurrent rotation races, expiry, all revocation commands, account isolation,
credential boundaries, and rendered Angular HTTP/session ownership. Package,
upgrade and owned-browser evidence is recorded in the A03 review report; do not
infer production deployment from local acceptance.
