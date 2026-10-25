# A03 revocable sessions — candidate evidence

Branch: `bugfix/alignment-a03-revocable-sessions`.
Recorded review base: `4072aa7f79cf67fbf5193566b84ab0b6bbcf1df5`.
Contract: [session lifecycle](../../security/session-lifecycle.md).
Plan: [accepted A03 plan](../../superpowers/plans/2026-10-24-alignment-a03.md).

## Result and scope

Persisted device identity is enforced on every protected request. The profile
panel lists/revokes devices and signs out all devices. One-hour access stays in
memory; a Secure HttpOnly host cookie supplies bounded rotating renewal.
Credential changes revoke access and refresh. Strict reuse revokes the complete
family. Native Web Locks, nonsecret cross-tab markers and immutable user/session/
generation checks prevent stale callbacks or retries from acting as a new account.
Initial matching-owner cookie restoration retains deliberate financial retry
drafts; account/device replacement cancels and clears them. No automatic financial
retry was added.

This is a local reviewed-candidate handoff, not a claim of merge, deployment or
production SMTP configuration. Root owns independent Standards/Spec review, final
native-browser acceptance and PR publication to dev. No worker push/PR/merge was
performed.

## Executed checks

Raw logs and frozen artifacts are outside git at
`/workspace/issue-context/alignment-implementation/batch4/A03`.

| Check | Exact command / interface | Result |
| --- | --- | --- |
| Backend | Java21 toolchain; `./mvnw -s /workspace/maven-proxy-settings.xml -B spotless:check verify` | 298 tests; zero failures/errors; formatting and packaged JAR pass |
| Frontend | Node22; `node node_modules/@angular/cli/bin/ng.js test --watch=false` | 306 tests across 40 files pass |
| Formatting | Node22; `node node_modules/prettier/bin/prettier.cjs --check 'src/**/*.{ts,html,scss,json}'` | Pass |
| Production build | Node22; `node node_modules/@angular/cli/bin/ng.js build --configuration production` | Pass; initial 518.20kB vs 500kB warning budget; previous baseline 501.19kB also warned; no budget change |
| Deployment tests | `python3 -m unittest discover -s deploy/tests -v` | 14 pass |
| Release shell scripts | ShellCheck 0.10.0 on `deploy/deploy.sh deploy/backup.sh deploy/deliver.sh` | Pass |
| CI workflow | Actionlint with the pinned ShellCheck executable | Pass |
| Disposable Compose | `docker compose -f compose.prod.yml -f deploy/compose.ci.yml config --quiet` with synthetic values | Pass; no production stack action |
| OpenAPI | Official OpenAPI 3.1 schema plus resolution of every local `$ref` | Valid; 183 references resolve |
| Root packaged HTTP | Real populated V13 → V14 PostgreSQL, actual packaged JAR | 55 requests pass on the frozen checkpoint; complete Java source is identical to the final JAR, with only an obsolete configuration comment corrected |

The backend runs use
`JAVA_HOME=/workspace/toolchains/jdk-21.0.12.1+1` and put its `bin` first on PATH.
Node commands use `npm exec --yes --package=node@22 -- node …`.

## Regression evidence

Sequential scenarios reproduced missing session-list404, logout-all 405,
logout404, missing secure cookie, unguarded refresh, credential-changed devices
remaining listed, excess devices, unbounded refresh attempts and a waiting
old-email login succeeding after confirmed email replacement. Each received a
minimal implementation before the next scenario. JWT fixture cutover retains
positive controls and independent expired/wrong-issuer/wrong-signature/HS512 and
credential-version checks, while explicitly rejecting sidless or foreign-owner
session claims.

Additional real PostgreSQL HTTP checks cover old consumed hashes after multiple
rotations, concurrent duplicate renewal (200/401 followed by winner-access401),
idle/absolute expiry, exhausted rotation budget, every explicit revoke command's
access and refresh effects, foreign/unknown revoke privacy and unchanged other
accounts. Root's packaged 55-request probe independently confirms those workflows
and preservation of populated USD finance rows and immutable retry receipts.

Frontend regressions observed stored new bearer credentials, missing bootstrap
renewal, absent captured-bearer logout and failure warning, stale login replacing
a newer account, missing native cookie serialization, missing shared renewal,
same-device profile drafts being discarded, missing cross-tab cancellation and
absent session controls. Same-device rotation now preserves the generation and
profile draft; a different account or same-user/new-session renewal never replays
an old POST. Tests also cover unseen newer markers, stale logout, terminal
renewal, manual device retries and local sign-out intent across reload after a
network failure.

Existing test fixtures authenticate through controlled HTTP instead of using
browser credential storage. Reload tests explicitly renew with the owned cookie;
existing group/expense/payment operation IDs, cancellation, profile privacy,
auth 429 feedback and session-generation cases remain covered.

## Frozen artifacts

- Final backend: `backend-final-candidate.jar`, SHA256
  `43a76e69321cc86980e33577a21f95c935f1a09af6784782dec4e31bc9250c4c`.
- Backend main-source manifest SHA256:
  `6567fd3e36250fc74ded727a772ddd2d4bc28041ca90fef5826798fe73f2e067`.
  `backend-final-manifest.json` records the exact command and the sole comment-only
  difference from root's 55-request checkpoint.
- Frontend: `frontend-306-browser/`; artifact-manifest SHA256
  `2e9520d9c13114364d9380cdff649a6382d2506d6e721d46754caee8ec8276ea`.
- Frontend main-source manifest SHA256:
  `ff43ee46b9e2a50ec9afdefe9db22cff2fa36b13b96315e9a2fdaa4af4c53b1a`.
  `frontend-306-manifest.json` pins the test/build/format logs and build warning.
- Independent root evidence: `independent-backend-full.json`,
  `independent-checkpoint-preservation.json` and their frozen-JAR manifests.
  Their owned resources were cleaned. Native-browser acceptance remains a
  separately reported root gate; no result is invented here.

## Merge and rollback dangers

Apply A03 V14 before C01 V15 or together; never enable Flyway out-of-order. Stop/
drain old binaries and force one sign-in: sidless legacy tokens are rejected and
mixed old/new instances cannot guarantee revocation. Keep the additive schema on
rollback. Prefer forward fixes; old auth binaries require reviewed all-instance
signing-key rotation, compatible frontend rollback and forced sign-in, and restore
older security behavior.

A lost refresh response or duplicate use can require sign-in. Revocation cannot
cancel work already authenticating/running at commit. Twenty families per account
and 2049 hashes per family bound retention; inactive accounts retain expired rows
until login cleanup. Attempt limits remain process-local and require shared
protection before horizontal scaling. Browser storage is used only for nonsecret
notifications/sign-out intent and existing owned uncertain drafts; native locks
and notifications have the compatibility limits documented in the contract.

Production Google Workspace SMTP setup, external mail delivery, FX/currency
implementation, main promotion and deployment remain outside this worker.
