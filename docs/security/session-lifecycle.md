# Authentication and session lifecycle

SplitEasy intentionally requires re-login when an access token expires. Login and
registration return an HS256 bearer access token and `expiresIn`; the default
`app.jwt.expiration-seconds` is 3600 seconds (one hour). There is no silent renewal,
refresh token, refresh/logout endpoint, or server-side device session store.
This is the current product contract; refresh remains a separate enhancement.

## Expiry and client logout

The resource server verifies the HS256 signature, configured `app.jwt.issuer`, and
token timestamps on protected requests. A02 additionally validates the persisted
account credentialVersion on every protected request. Missing legacy version
claims mean zero; malformed/negative versions and missing accounts are rejected. Spring Security's default timestamp
validator permits 60 seconds of clock skew; the nominal one-hour lifetime is not
an exact wall-clock cutoff. An expired or otherwise invalid bearer token receives
401. These protected-route failures are produced by the Spring Security filter chain,
so their error body is not guaranteed to match the application `ErrorResponse` schema.
The browser keeps the access token and user summary in localStorage across page reloads; local identity state is not proof of server authorization. There is
no client expiry timer. The next protected API request that returns 401 clears
the matching client session and redirects to `/login`. A late 401 from an earlier
session cannot clear a subsequent login.

Signing out calls `AuthService.logout()`: it synchronously resets account-scoped
state and pending UI operations, removes the stored token/user, and clears the
current identity. It does not contact the server or invalidate a copied token.
A previously issued token remains usable until expiry (subject to clock skew)
or a credential boundary. Password reset, authenticated password change and
confirmed actual email change atomically increment credentialVersion, invalidating
all older tokens after commit. Requests authenticated before commit may finish.
Client logout itself does not increment this version or affect another device.
Selective per-session revoke, refresh and logout-all UI remain A03 work. localStorage is
accessible to same-origin JavaScript, so bearer-token theft through script
compromise remains a risk; client cleanup cannot revoke stolen copies.

## Requirements before adding refresh or revocation

Agree on and document these requirements before implementation; they are not
additional capabilities beyond A02 credential-boundary revocation:

- Define which events revoke one session versus all account sessions, including
  logout, suspected theft and operator intervention. A02 already revokes all old
  credentials after password reset/change and confirmed email changes. Specify
  when access must stop, including requests already in flight and multi-instance
  deployments. Immediate access-token revocation requires server-side enforcement;
  revoking only refresh credentials leaves existing access tokens usable to expiry.
- Define server-side session identity, persistence, retention and cleanup, access
  and refresh absolute/idle lifetimes, and secure storage on both sides. Do not
  persist raw refresh credentials server-side. For browser cookie storage, define
  Secure, HttpOnly and SameSite settings plus CSRF/CORS protection and deployment
  origin constraints. Do not simply add long-lived refresh tokens to localStorage.
- Require refresh rotation on use, atomic consumption, and replay detection with a
  defined session-family revocation response. Specify concurrent refresh, retry and
  lost-response behavior so a consumed credential cannot be reused or restored by
  a race. Keep credentials out of logs and error responses.
- Define client recovery and account-state cleanup for expiry, failed refresh and
  revoked sessions. Retain signature, issuer, algorithm and timestamp enforcement.
  Test real filter/endpoint behavior, rotation, expiry, replay, concurrent use,
  revocation propagation and account isolation using disposable credentials/data.

Changing the shared signing secret is an operator action that invalidates all
existing tokens once all API instances use the new key. It requires coordinated
configuration/restarts and forces every user to re-login. It is not selective
session revocation or a substitute for defining the requirements above.

## Verification

`JwtSecurityIntegrationTest` sends real signed tokens through the protected
`GET /api/groups` filter chain with throwaway PostgreSQL: a valid control succeeds;
expired, wrong-issuer, wrong-signature and same-key HS512 tokens each receive 401.
The expiry case is outside clock-skew tolerance. A02 also checks malformed,
negative, mismatched and explicitly null credential versions, invalid/missing
accounts, legacy version-zero acceptance and revocation after a password change. Existing `AuthFlowIntegrationTest`
covers login/registration and missing/malformed tokens. Frontend auth and
session-isolation specs cover local cleanup, protected-request 401 handling and
stale-session callbacks. These checks use no production data or credentials.

A02 lifecycle details, SMTP configuration, one-use mailbox challenges and the
security restriction on old-binary rollback are in [account recovery](account-recovery.md).
