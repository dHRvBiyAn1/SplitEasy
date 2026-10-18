# Issue #62 verification — 2026-10-18

Base: `dev` f68d818. The source had public login/registration without an app attempt gate. The user explicitly authorized adding application limits; external Cloudflare rules were not inspected and are not characterized as absent.

Implemented policy and operational limits: [auth-abuse.md](../../security/auth-abuse.md). Login allows 30/IP/5 minutes and 5/(normalized account, IP)/5 minutes. Registration allows 20/IP/hour, configurable with a positive `APP_AUTH_REGISTRATION_LIMIT`. Authentication runs after validation and before account lookup/BCrypt; all attempts including successes count. No migration, new dependency, signing/issuer change or frontend session change.

## Regression and full checks

Baseline HTTP regressions failed twice on missing 429 responses; both frontend forms failed their friendly-429 assertions. After implementation:

| Command | Result |
| --- | --- |
| `cd backend && ./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` (Java 21) | 146 tests, zero failures/errors; package and Spotless passed |
| `cd frontend && npm test -- --watch=false` | 112 tests across 23 files passed |
| `cd frontend && npx prettier --check "src/**/*.{ts,html,scss,json}"` | passed |
| `cd frontend && npm run build -- --configuration production` | passed, no build warnings |
| `python3 -m unittest discover -s deploy/tests` | 12 passed |
| pinned production Caddy image, `caddy validate --config /etc/caddy/Caddyfile` | valid configuration |
| `bash -n` per deploy script and `git diff --check` | passed |

The six deterministic limiter tests use a monotonic fake clock and real concurrent calls: exact account/IP/endpoint boundaries, normalization, expiry/reset, rounded non-extending Retry-After, 100 concurrent account attempts accepting exactly five, and 10,000 active buckets rejecting new identities without evicting existing counters and recovering after expiry. Two full Spring/PostgreSQL API tests cover normal success/conflict/401, 429 JSON/header, forwarded-header spoofing in direct development, and client/endpoint isolation. Existing integration tests retain the real limiter and use separate test-client IPs.

## Actual HTTP and browser checks

Only throwaway PostgreSQL and local JVMs/containers were used. The verified jar ran under production/native-forwarding and development/no-forwarding profiles. Pinned Caddy used the checked-in config, changing only its upstream address for the local host JVM. It joined a private `172.30.62.0/24` network and a deliberately untrusted public-range `203.0.113.0/24` test network. Existing session proxy settings were retained with the local JVM upstream added to Caddy's NO_PROXY.

Observed HTTP results:

- Trusted private peer's CF visitor metadata selected separate real client budgets: visitor A's sixth login returned 429 despite rotating XFF/Forwarded; visitor B's same-account attempt returned 401 independently. This exercises Caddy resolution/replacement and the actual native Tomcat valve together.
- Requests from the untrusted public-range peer rotated CF-Connecting-IP, XFF and Forwarded but shared the real peer's bucket: sixth attempt returned 429.
- The actual direct development server likewise ignored forged forwarding headers and returned 429 on the sixth attempt.
- 429 responses carried a positive Retry-After within the policy window and exposed that header via CORS.
- Six users registered successfully behind one IP; conflicts consumed the remaining registration allowance, and request 21 returned 429. Login and a previously issued token's protected `/api/groups` request still returned 200.

Headless system Chromium loaded the production frontend artifact. Browser interception relayed each submitted form to the real Caddy/Java endpoint and fulfilled the actual 429 response (only the browser test origin's CORS grant was adapted). Both login and registration displayed the friendly wait notice, re-enabled the submit button, retained the preexisting session token and URL, and issued no automatic retry.

Local execution evidence is retained outside the checkout in `/workspace/issue-context/62-{red-http,red-frontend,full-backend,full-frontend,prettier,build,deploy-tests,caddy-validate,http-smoke,browser-smoke}.log`; browser screenshots are `62-login-429.png` and `62-register-429.png`. No passwords/tokens were printed or included in this report. Independent read-only source review found no blockers.

## Limits

Local counters reset on restart and do not coordinate multiple Java instances. At 10,000 active buckets new authentication identities fail closed until an expiry, at most one hour; existing authenticated API access remains usable. Account scope is per IP, so distributed guessing remains possible. Private proxy networks and no published application ports are required for native forwarding trust. Live Cloudflare configuration, production traffic behavior and deployment were not changed or tested. Existing duplicate-registration 409 behavior and broader enumeration/monitoring policy remain separate work.
