# Pages, Lightsail and Tunnel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. The human partner must review this plan and choose the execution method before implementation.

**Goal:** Deploy Angular to Cloudflare Pages and expose the Dockerized Lightsail backend through Cloudflare Tunnel and private Caddy.

**Architecture:** `split-easy.io` serves the verified Angular artifact. `api.split-easy.io` routes through cloudflared to private HTTP Caddy, which preserves `/api` when proxying Java. PostgreSQL stays private with persistent storage; GitHub Actions deploys the backend before publishing Pages.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL 16, Angular 21, Node 22, Docker Compose, Caddy, cloudflared, Cloudflare Pages/Wrangler, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-15-pages-lightsail-tunnel-design.md` (user-approved 2026-10-15).

## Global Constraints

- Development stays on `dev`; production deployment follows promotion to `main`.
- Public names: `split-easy.io` for the frontend and `api.split-easy.io` for the backend. Associate the apex directly with Pages; no app subdomain or redirect is required.
- Local Angular development continues using relative `/api` and the existing proxy.
- The Pages build uses `https://api.split-easy.io/api` through Angular build configuration, without a new runtime configuration framework.
- Backend CORS allows exactly `https://split-easy.io` in production.
- No service publishes a public application port. Port 22 remains a separate administration/deployment path.
- Keep the tunnel token in a server-only file mounted read-only into cloudflared, using `--token-file` and a compatible pinned image.
- JWT and database secrets remain server-side; frontend bundles contain only public configuration.
- Keep `DEPLOY_ENABLED` as the last activation switch after the server, tunnel, Pages project, DNS and secrets are ready.
- Never create real users or groups during public production smoke checks.
- Rollback never restores PostgreSQL automatically; backend rollback is schema-aware and Pages rollback is separate.
- No commit, push, resource purchase, account configuration or remote deployment without applicable user authorization. Preserve existing local research files and other uncommitted changes. The user's commit-only-when-asked rule overrides skill commit checkpoints.

## Review Focus

1. Misleading URL prefixes, credentials in URLs and normalized `..` paths must not receive a JWT; covered in Task 1.
2. Cross-origin login preflight and disallowed origins must behave correctly through Caddy; covered in Task 2.
3. A missing/unreadable token file or disconnected tunnel must prevent successful public deployment without leaking credentials; covered in Tasks 2–3.
4. Bad release manifests, inherited environment overrides, and invocation through release aliases must not bypass digest/backup/pointer guards; covered in Task 3.
5. Backend failure, Pages failure and superseded main revisions must produce the documented partial-rollout behavior; covered in Task 4.

---

## File map

| Files | Responsibility |
| --- | --- |
| `frontend/src/environments/environment.ts`, `environment.production.ts`, `frontend/angular.json` | Compile-time local/production API address |
| `frontend/src/app/core/api/api.service.ts`, `api.service.spec.ts`, `core/auth/auth.interceptor.ts`, `auth.interceptor.spec.ts` | API addressing and safe token/401 boundaries |
| `deploy/Caddyfile`, `compose.prod.yml`, `deploy/compose.ci.yml`, `deploy/.env.example` | Private origin stack, tunnel token file, loopback-only smoke configuration |
| `deploy/deploy.sh`, `backup.sh`, `smoke.py`, `tests/test_release.py`, `tests/test_stack.py` | Backend digest releases, backups, failure guards and meaningful container checks |
| `.github/workflows/ci-cd.yml`, `.github/dependabot.yml` | Verified artifact delivery, backend publication and Pages deployment |
| `frontend/package.json`, `package-lock.json` | Exact Wrangler development dependency for repeatable Pages deployment/local serving |
| `docs/deployment/lightsail.md`, `README.md`, `Agents.md` | Complete new runbook, operator entry point and living memory |
| Remove `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/Caddyfile` | Retire the obsolete Angular runtime container after CI migration |

### Task 1: Connect Angular to its separate API safely

**Interfaces:** `environment.apiBase: string` is `/api` locally and `https://api.split-easy.io/api` in production. Export `API_BASE` from this value. Add `apiPath(url: string, base: string = API_BASE): string | null` in the existing shared API module: return the normalized pathname suffix under the configured API root (including leading slash), or null for other requests. The interceptor uses this classification for token attachment and 401 handling; paths beginning `/auth/` are excluded.

- [x] Add failing tests in `api.service.spec.ts` for both base modes: `/groups` is recognized; `/auth/login` and `/auth/register` are classified as API auth routes; foreign origins, `/api-admin`, `https://api.split-easy.io.evil/api/groups`, credential-host tricks, and `/api/../outside` yield null. ApiService requests use the configured root.
- [x] Add failing interceptor tests for untrusted-host/prefix requests receiving no Authorization header and causing no logout on 401; preserve existing API-token and logout assertions and add register/auth-401 exclusions.
- [x] Run `cd frontend && npm test -- --watch=false` on Node 22 and confirm the new cases fail for the current implementation.
- [x] Implement the two environment files and production `fileReplacements`, then update shared API/interceptor behavior. Normalize URLs before origin/path comparison; keep relative local addresses working. Do not add another HTTP layer or runtime configuration loader.
- [x] Run the full frontend suite, Prettier check, and `npm run build -- --configuration production`; verify the emitted browser bundle contains `https://api.split-easy.io/api` and no server secrets.

### Task 2: Establish a private Caddy origin and tunnel stack

**Interfaces:** Compose services are `postgres`, `backend`, `caddy`, `cloudflared`; production defaults have no `ports`. Caddy accepts HTTP on port 80, provides private `/health` on 8081, routes only `/api` and `/api/*` to `backend:8080`, and returns 404 elsewhere. cloudflared origin is `http://caddy:80`. Server `.env` keys include `PUBLIC_ORIGIN=https://split-easy.io`, `API_ORIGIN=https://api.split-easy.io`, `POSTGRES_PASSWORD`, `APP_JWT_SECRET`, `TUNNEL_TOKEN_FILE` (absolute host path). `BACKEND_IMAGE` comes from the immutable release manifest.

- [x] Add `test_stack.py` checks using actual Compose JSON for no published ports, private database network, fixed CORS origin, token-file mount and missing required configuration failure; assert no secret values are printed by test commands.
- [x] Extend `deploy/smoke.py` with OPTIONS login/group requests: allowed-origin responses include the exact Origin and permit Authorization; a foreign origin receives no allow-origin grant. API routes return JSON, `/actuator/*` and `/login` return 404 through Caddy. Preserve disposable auth/group and persistence checks; remove old Angular HTML assertions from this API test.
- [x] Run the tests against the existing stack and record the expected failures before switching the stack.
- [x] Implement `deploy/Caddyfile`, production Compose and env example; preserve PostgreSQL volume identity and backend readiness/limits. Pin verified Caddy/cloudflared images. Restrict connector reachability to Caddy and external tunnel connectivity; keep PostgreSQL inaccessible to Caddy/cloudflared. Mount the token file read-only and verify its readability with the chosen non-root UID. Disable the connector in CI through `deploy/compose.ci.yml` and publish Caddy only on `127.0.0.1:18080` in that override. Do not introduce an unrelated certificate service.
- [x] Validate Caddy with its real image. Run `docker compose -f compose.prod.yml -f deploy/compose.ci.yml up -d --build --wait`, then smoke, recreation/persistence and a real dump/restore against a disposable database. Verify malformed/missing token configuration fails before production activation. CI needs no production token or real tunnel.

### Task 3: Adapt backend releases without weakening recovery

**Interfaces:** `release.env` has exactly one `BACKEND_IMAGE=ghcr.io/...@sha256:<64 hex>` record; release packages contain `compose.prod.yml`, `deploy/Caddyfile`, `deploy/deploy.sh`, `deploy/backup.sh`. Keep `/opt/spliteasy/.env`, token file and `postgres-data` outside the immutable release directories. `API_ORIGIN` determines public API health smoke; frontend origin is never used for API smoke.

- [x] Extend the existing release fixture for the one-image manifest, Caddy/connector service names and `API_ORIGIN`. Cases include duplicate records with/without final newline, configuration error, missing/unreadable token, pull/backup/startup/readiness/public-smoke failure, unsafe API origin, exported environment overrides and both `current`/`previous` aliases. Failed releases never promote `current`; backup precedes Java startup.
- [x] Run `python3 -m unittest discover -s deploy/tests -v` and confirm the new release expectations fail before edits.
- [x] Update deploy/backup scripts to consume the new files and unset all configuration overrides that could supersede authoritative env files. Pull/start Caddy and cloudflared deliberately, wait for Java/Caddy health, then poll public `API_ORIGIN/api/health` with bounded retries. Preserve locks, verified dump, previous-pointer behavior and schema-aware rollback; do not promote on tunnel errors.
- [x] Run release tests and ShellCheck. Repeat successful local smoke and backup checks after these script changes; remote tunnel connectivity remains a separately reported live check requiring actual account/server setup.

### Task 4: Deliver tested backend and Pages artifacts from main

**Interfaces:** frontend CI produces an artifact named `pages-${GITHUB_SHA}` containing `frontend/dist/frontend/browser`; the container job produces `backend_image` as a digest. One serialized production job consumes both. Production environment adds variables `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_PAGES_PROJECT=spliteasy` and secret `CLOUDFLARE_API_TOKEN` (Pages Edit); existing SSH variables/secrets remain. `DEPLOY_ENABLED=true` remains repository-scoped.

- [x] Add small offline workflow guard assertions to `test_stack.py`: PR/dev never deploy, production requires main/activation and all check jobs, frontend upload occurs after backend activation, failed SSH prevents Pages upload, and artifact identity uses the current SHA. Use the existing test infrastructure; no new testing framework.
- [x] Select an exact compatible Wrangler version from official releases and lock it in frontend devDependencies. Update the frontend job to upload the successful production artifact; update container job to test only Java/Caddy/PostgreSQL and publish Java only. Use commit-pinned artifact Actions.
- [x] Update the single production job to retrieve the artifact for the same workflow run, verify all required settings, skip superseded main revisions, package the Task 3 release, activate it via strict SSH, then run locked Wrangler Direct Upload with `--project-name=spliteasy --branch=main`. Preserve false/no-op behavior for superseded revisions across both backend and frontend operations.
- [x] Add bounded public Pages checks for `/login` and a deep link after upload. A Pages failure must fail the job while preserving the healthy backend; do not roll back the database. Retire frontend Docker/runtime files and their Dependabot entry, keeping the frontend Node 22 installation path in CI.
- [x] Run workflow guard assertions, ShellCheck and Actionlint; exercise a mocked deployment failure sequence proving no Pages upload follows a failed backend and an upload failure never invokes backend/database rollback. Inspect artifact contents and local Pages serving through the pinned Wrangler CLI for HTML/deep links. Live Pages upload is not attempted with missing credentials.

### Task 5: Write the operator guide and finish verification

**Interfaces:** `docs/deployment/lightsail.md` becomes the current single runbook for this architecture; README links it and Agents memory describes the implemented state, separately from pending live activation.

- [x] Replace direct A-record/public-80/443 instructions with ordered Lightsail provisioning, Docker/SSH bootstrap, private env/token file permissions, GHCR access, remotely managed tunnel, Pages Direct Upload project/custom domain, cache bypass for the API host, GitHub settings and final activation. Keep port 22's separate runner access explicit. Provide token rotation, logs, daily off-host backups, real restore and independent backend/Pages rollback instructions. Preserve the initial SSH session until the deploy key works.
- [x] Update README and Agents memory. Include `http://caddy:80` and both exact public hostnames; warn against DNS pointing directly at the Lightsail IP for the tunnel API. Explain previews have no automatic production CORS access and partial publication is possible. Mark prior combined-stack guidance superseded.
- [x] Run Java 21 `./mvnw -B spotless:check verify`; Node 22 frontend full tests, Prettier and production build; script/Compose/workflow checks; disposable stack auth/CORS/persistence and dump/restore verification. Record actual results and limitations in the runbook or task report. Do not claim live success based on local checks.
- [x] Review the entire change for token leakage, publicly exposed ports, premature frontend publication and database-loss risks. Show the user a concise changed-files/testing report. Leave changes uncommitted unless requested; a later PR requires full suites and a Testing section.

## Execution handoff

Recommended: native implementation in this session. These tasks share the same API/deployment interfaces and the same repository files, so sequential implementation limits coordination overhead. A final independent review is required by the execution workflow. Subagent-driven implementation is also available if the user prefers per-task independent review.

User approved native execution in this session. All local implementation/verification tasks are complete; external activation remains pending. External setup/activation will require the actual Lightsail instance and Cloudflare/GitHub account settings; never ask the user to paste secrets into chat.


## Execution notes — 2026-10-15

- User confirmed the apex frontend and API subdomain; no redirect or Pages Function is used.
- Native Angular `API_BASE_URL` InjectionToken supports production-origin tests; Angular's runner rejects relative module mocking. It defaults to the compile-time environment and adds no runtime configuration loader.
- Actual delivery-script failure tests replace source-text workflow assertions; workflow gates were inspected and Actionlint passed.
- Debian Node 22 is used for local Pages preview because Cloudflare workerd needs glibc; production GitHub runners already use Ubuntu.
- Implementation was kept local until the user explicitly authorized committing/pushing dev and opening a PR to main.
- Verification: backend 108, frontend 57, deployment test methods 10; real Caddy/API CORS/auth/persistence/backup restore and local Wrangler SPA preview passed. Independent final review had no Critical/Important findings; its whitespace finding was fixed.
- Live provider configuration, token/DNS/cert activation and public deployment are not verified until the user's accounts/server are configured. See docs/deployment/2026-10-15-verification.md.

Region amendment (2026-10-15): user explicitly selected the existing Sydney instance (`ap-southeast-2`) after the readiness audit. This supersedes the original Mumbai preference; the deployment topology and CI/CD interfaces are unchanged. Commit/push on dev and a PR to main are now authorized; no merge or live deployment is authorized by that publication request.
