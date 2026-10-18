# P2 integration verification — 2026-10-18

Integrated PRs #72–#93 into a local branch from `dev` (`f68d818`). Each exact PR head is retained through a merge commit; conflict resolution preserves both issue behaviors and their regression tests. The already-released `main` history (`2a3a1af`) was synchronized without changing the application tree.

## Testing

- Backend: Java 21, `./mvnw -B spotless:check verify` (using the local Maven proxy settings) — 207 tests, zero failures/errors/skips; package succeeds.
- Frontend: `npm test -- --watch=false` — 187 tests in 26 files pass. Full source Prettier check and production build pass.
- Deployment: `python3 -m unittest discover -s deploy/tests -v` — 12 tests pass. ShellCheck 0.10.0 and Actionlint 1.7.7 pass.
- Chromium against the combined Angular application: all eight dashboard refresh completion/error orderings preserve the newest result; percentage precision/negative-share validation, rounded integer POST values and exact zero-share omission pass. API responses were synthetic; no production data was used.
- Independent integration review found no blockers in manual resolutions or overlapping auth, payment, expense, dashboard, group and profile changes. All 22 original PR heads are ancestors; whitespace and conflict-marker checks are clean.

Final GitHub checks on the published `dev` head must pass before opening the release PR. The existing CI also builds the combined backend container and exercises the private Caddy/PostgreSQL API and restart persistence. This report does not claim deployment.

## Release considerations

- V9 adds nullable payment requester/operation identifiers and their constraint/index. Legacy requests without IDs remain additive; client retry identity is held in memory and does not span reloads or tabs. Follow the existing backup and schema-aware rollback runbook.
- Login/registration limits are process-local and reset on restart. Multiple instances require shared enforcement. Forwarded-client trust requires the documented private Cloudflare Tunnel/Caddy topology.
- Ownerless legacy profile preferences are discarded; users re-enter those personal values. New preferences belong to the immutable account ID.
- Sessions retain the documented one-hour access token, clock-skew tolerance and client-only logout contract. Refresh and immediate server-side revocation are separate enhancements.

The user authorized merging these PRs into `dev` and creating a `dev` → `main` release PR. Merging that release PR and deploying are outside this instruction.
