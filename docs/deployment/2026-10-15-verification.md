# Pages + Lightsail Tunnel verification — 2026-10-15

Implementation is prepared on `dev` for the user-authorized commit/push and PR to `main`. The deployment target is the existing Sydney Lightsail instance, retained at the user’s request. The user selected native implementation, `split-easy.io` for Angular Pages, and `api.split-easy.io` for Tunnel/Caddy/Java. This report records verification before publication. No AWS purchase, account setting, DNS record, secret upload or public deployment was performed by the implementation task.

| Verification | Result |
| --- | --- |
| Java 21 full `spotless:check verify` | 108 tests passed; build successful |
| Clean Node 22 install, frontend suite, Prettier and production build | 57 tests passed; formatting and production bundle successful |
| Production API configuration | Actual emitted JS contains `https://api.split-easy.io/api` |
| Token-boundary regression | New tests failed on the original unrelated-request JWT behavior; passing implementation scopes token/logout handling to configured API origin/path |
| Separate production API interceptor fixture | JWT attached to private API requests; login/register/foreign-host requests excluded |
| Release/delivery/Compose suites | 10 test methods passed; release cases include malformed/duplicate manifests, token missing/empty/public/unreadable, configuration/pull/backup/startup/readiness/public-smoke errors and symlink recovery |
| Delivery ordering | Failed backend prevents Pages; failed Pages does not roll back backend/DB; dev and superseded main have no production effects |
| Production Compose | No published application/database ports; database network internal and unavailable to Caddy/cloudflared |
| Real Caddy configuration | Validated with the pinned Caddy 2.11.7 image |
| Disposable PostgreSQL/Java/Caddy stack | Healthy; auth/group/dashboard smoke passed; management/unknown routes rejected |
| Cross-origin API | Exact frontend Origin/Authorization preflight allowed; untrusted Origin rejected |
| Persistence | Created group survives container recreation and fresh login |
| Backup recovery | Real custom-format pg_dump restored into a separate disposable database; expected group count 1 |
| Cloudflare local Pages runtime | Pinned Wrangler 4.151.0 served `/login` and a group deep link with Angular HTML |
| ShellCheck / Actionlint / whitespace | Passed |
| Independent fresh review | No Critical/Important findings; minor whitespace issue fixed |

Wrangler's workerd binary requires glibc, so the local preview was verified in Node 22 Debian rather than Alpine. The GitHub workflow uses Ubuntu and Node 22. Angular's test runner rejects relative `vi.mock`, so production-origin behavior uses the native Angular API-base InjectionToken in the test fixture.

Live tunnel connectivity, Cloudflare credentials, custom-domain DNS/certificate activation, GHCR access from the actual VM and public Pages publication remain to be verified after account setup. Follow [the complete runbook](lightsail.md). A green local stack does not prove the public deployment is active.

Readiness audit: verified deploy SSH, Docker/Compose, private server .env and expected origin/secret-presence checks. GitHub production setting/secret names and main branch policy exist; frontend placeholder is reachable. The initial audit found an empty tunnel token and API disconnected (1033). After the user populated the file, a metadata-only SSH recheck confirmed a nonempty regular file owned by 65532:65532 with mode 0400. Token contents were not read. Connector authentication, GitHub secret values, actual server registry authorization and live deployment remain unverified until the first deployment.

## CI portability and dependency review

The first GitHub run failed in the Compose boundary test, with `KeyError: create_host_path`: normalized JSON can omit false booleans. Reproduced on Linux Compose 2.39.2 before changing the assertion. The corrected assertion accepts an omitted false field while retaining the bind/read-only checks. Deployment suites pass on Linux Compose 2.39.2 and local Compose; ShellCheck/Actionlint pass. The Linux mutation check rejects `create_host_path: true`. Full backend (108) and frontend (57), formatting and production build were rerun successfully. GitHub CI must confirm the combined workflow update.

Dependabot review: PRs #30/#31/#32 update setup-java/checkout/setup-node; their upstream tag commits were verified and the digest-pinned updates consolidated into dev, including the new deployment job. Java remains 21 and Node remains 22. PR #33 changes both Java build/runtime to 25 and fails its container compile; it is deferred as a toolchain migration. PR #29 changes the removed frontend Dockerfile and is obsolete under Pages. Monthly version updates now target dev and are grouped by ecosystem; Java major base-image updates are ignored. This configuration becomes active when promoted to main; default-branch security updates remain separate, per [GitHub's Dependabot reference](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference#target-branch).
