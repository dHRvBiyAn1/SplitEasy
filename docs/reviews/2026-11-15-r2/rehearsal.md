# R2 release rehearsal (preparation only)

Date: 2026-11-15. Rehearsed commit: **`85e9de3`** (`dev` head after PR #132). Production = `v0.2.0` (`9316f6e`, schema V18).
No push to `main`, no release PR, no deployment, no production access, no production data. All containers,
processes, worktrees and temp files were created and removed by this run.

Note on commits: gates were first run at `cae459b`; `dev` then moved to `85e9de3` with a frontend-only change
(8 files, Beta language labels; `git diff cae459b 85e9de3` touches nothing under `backend/`, `deploy/`,
`compose.prod.yml` or `.github/`). Frontend gates and the browser pass were re-run on `85e9de3`. The backend
suite, the deploy checks and the upgrade rehearsal ran on `cae459b`, whose backend source is identical, so they
are not repeated.

## Checklist

| # | Item | Result |
|---|---|---|
| 1a | Backend `./mvnw -B clean spotless:check verify` (Java 21) | **PASS** — 460 tests, 59 classes, 0 failures/errors/skips; Spotless clean (`cae459b`, backend source identical at `85e9de3`) |
| 1b | Frontend on Node 22.17.1: `npm ci`, Prettier, `ng test`, production build | **PASS** at `85e9de3` — Prettier clean; 457 tests / 70 files; build OK, initial bundle 621.68 kB (warning vs 500 kB budget; non-blocking, known) |
| 1c | Deploy tests `python3 -m unittest discover -s deploy/tests` | **PASS** — 14 tests |
| 1d | ShellCheck (`deploy.sh backup.sh deliver.sh`), actionlint | **PASS** (run via throwaway Docker images, removed afterwards) |
| 1e | CI `containers` job (Docker build + Caddy/Postgres smoke) | **NOT RUN** locally; CI covers it |
| 2 | Seeded upgrade v0.2.0 → dev head (V19→V30) | **PASS** — see below |
| 2b | Rehearsal on a copy of the real production dump | **NOT DONE — blocked** (host key, see below) |
| 3 | Old binary against upgraded schema | **DONE** — starts, but its writes are unsafe (see below) |
| 4 | Browser pass at 1280 and 390 | **PASS with 2 findings** — see below |
| 5 | Cleanup | **DONE** |

## 2. Seeded upgrade rehearsal

Setup: disposable `postgres:16` on 127.0.0.1:55432. `v0.2.0` built from the tag in a temporary worktree
(removed). Seeded through the v0.2.0 HTTP API: 5 users; 5 groups in INR, JPY, KWD, USD and a settled USD group;
all five split types; 5 payments; 17 expenses including one deleted-then-restored and one left deleted;
2 guest participants (one claimed by an account through a real email invitation, using a local SMTP sink);
3 saved friends. v0.2.0 was stopped completely before the dev-head jar started on the same database.

- Flyway: `Current version 18` → `Successfully applied 10 migrations, now at v30`. Versions
  1–21, 23, 25–30, all `success=true` (22 and 24 are the intended gaps). 28 migrations validated.
- Row counts for every pre-existing table identical before/after: users 5, groups 5, group_memberships 17,
  expenses 17, expense_participants 59, payments 5, participants 7, participant_account_links 6,
  group_participants 19, group_invitations 1, friends 3, expense_revisions 20, auth_sessions 5,
  refresh_credentials 5, create_operations 0, account_challenges 0.
- New tables: `expense_contributions` 17, `ledger_revisions` 5 (one IMPORTED baseline per payment); all others 0.
- Per-currency totals identical. Live expenses amount = shares: INR 2,433,284; JPY 126,236; KWD 67,844;
  USD 316,777. Payments: INR 150,000; JPY 5,000; KWD 3,000; USD 21,000 (2). 1 deleted expense retained.
- Net balance per (group, currency, participant): md5 identical before/after. md5 of expenses, payments and
  expense_participants (including `split_value`) identical.
- Group balances sum to zero per currency: 0 violations, before and after.
- Contribution backfill: 0 expenses with contribution count ≠ 1, sum ≠ amount, or contributor ≠ `paid_by`
  (17 expenses, 17 contributions).
- Foreign keys: 58 checked, 0 orphans.
- API level: 39 responses (dashboard totals, group balances, pairwise `/mine` for 5 users × 5 groups) byte-identical
  before and after. The pre-upgrade access tokens kept working against the new backend, so **no re-login is
  needed** for sessions in flight. The refresh-cookie path across the upgrade was not exercised.

## 2b. Real production dump — not done

Stopped as instructed. `LIGHTSAIL_HOST`/`LIGHTSAIL_USER` were read from the `production` environment variables
(`gh`), but `32.236.160.58` has no entry in `~/.ssh/known_hosts` and `LIGHTSAIL_KNOWN_HOSTS` is a secret that
cannot be read. With `StrictHostKeyChecking=yes` the connection was refused (`Host key verification failed`).
I did not use trust-on-first-use or `ssh-keyscan`, which does not authenticate the server. Nothing was copied,
restored or read from the server, and no personal data was touched.
To unblock: add the verified host key (compare the fingerprint in the Lightsail console) to `~/.ssh/known_hosts`,
or hand over the dump another way. Still open before R2.

## 3. Old binary on the upgraded schema (on a copy of the upgraded DB)

- v0.2.0 **boots and serves**: Flyway validates 28 migrations, logs
  `WARN Schema "public" has a version (30) that is newer than the latest available migration (18)`, applies
  nothing, Hibernate `validate` passes, `/api/health` UP, reads return 200. Flyway does not block it.
- Its **writes are not safe**. An expense POST returns 201 but writes **no `expense_contributions` row**; the dev
  head then computes that payer as having paid 0, and the group's USD nets sum to **−10,000** (the
  expense amount) instead of 0. A payment POST returns **500** (V20 columns it does not know).
- Rule confirmed: rollback past V19/V20/V23 is forward-fix only. Drain the old binary completely before the new
  one starts. Never run both. `deploy.sh` recreates a single container, so there is no overlap window; a
  manually started second backend would be the way to hit this.

## 4. Browser pass

Dev-head jar + production Angular build (built in a temp copy with the API base set to `/api`, so the public API
is never contacted; the real production file is untouched), served with an SPA proxy. Own Chrome 157 with its own
`--user-data-dir`, driven over CDP. Seed data above plus API-created: multi-payer expense, refund, payment
correction, personal expense, recurring schedule, balance reminder.

Verified in the UI as Ana: login; 13 routes at both widths (dashboard, friends, friend detail, group, payments,
settings, history, recurring, activity, notifications, profile, a second group, a non-member group);
multi-payer receipt with per-contributor lines; **Record refund** (balance moved correctly); **payment
correction** (row updates immediately; ledger shows IMPORTED + 2 CORRECTED); **Settle all in JPY and INR** with a
friend (2 batches, allocations 8,339 + 16,416; page shows INR 0.00 / JPY 0); personal expense listed;
recurring schedule listed; notification bell (badge 1 → cleared by Mark all as read); activity feed; **History CSV
download** (28 lines, header OK); language switch to hi/es/fr/en (labels show "(Beta)"); **account closure
blocked while unsettled** (409, account unchanged) and **zero-balance closure succeeds** (signed out to
`/login?reason=account-closed`, email tombstoned).

- Console/page errors from the app: **none**. HTTP errors seen, all expected: 403 on a non-member group (×2),
  401 on `/auth/refresh` when signed out (×2), 409 on the blocked closure. Backend log: 0 `ERROR` lines.
- Overflow at 1280: none on any route.
- Overflow at 390: see finding F1. Hindi has none; French and English 15 px; Spanish 24 px. No other element
  overflowed in any language on 9 pages (dashboard, friend, group, payments, settings, history, recurring,
  notifications, profile).
- Harness gotcha: headless Chrome reports `visibilityState=hidden`, and the screens deliberately skip foreground
  reloads then, so lists looked stale after mutations. With focus emulation on, every list refreshed. Not an app bug.

Screenshots (9, in this directory): `dashboard-1280`, `dashboard-390`, `lang-es-dashboard-390`,
`lang-hi-group-390`, `payments-390`, `history-1280`, `expense-receipt-multipayer-1280`, `friend-settled-1280`,
`account-closure-blocked-1280`.

## Findings

**Release blockers**
- None found in the code, migrations or data path.
- Process gate still open: step 2b (real production dump) has not run; see above.

**Fix before or soon after release (user-visible, no data impact)**
- **F1 — header overflows at 390 px.** The page is 15 px wider than the viewport (24 px in Spanish) and the
  account avatar is clipped at the right edge on every authenticated page (`A.account`, `.dc-avatar`). Probably the
  "Notifications" label added next to the bell. Reproduce: any logged-in page at 390 wide.
- **F2 — blocked account closure shows the wrong message.** Profile → Close account with an unsettled balance
  returns 409, correctly, but the UI shows "Reload or upgrade the app to use financial participants safely."
  instead of the "Settle your balances: …" list. The same call from the API with `X-Participant-Version: 1` returns
  the correct text. Likely cause: `ApiService.ledgerOptions` (`frontend/src/app/core/api/api.service.ts:42`) does not
  add the contract headers for `/me/closure`. Zero-balance closure works.

**Follow-ups**
- Old-binary writes silently unbalance a group (section 3). The drain rule is documented, but a cheap DB guard
  (for example a check that every expense has contributions) would turn a silent error into a loud one.
- Initial bundle 621.68 kB vs 500 kB budget (warning).
- hi/es/fr are machine-drafted; the Beta label now ships, native review still pending.

## Not verified

Real production dump (2b); refresh-cookie behaviour across the upgrade; Docker/Caddy/Postgres smoke job; interactive
flows at 390 (390 got the route and language pass, not the clicks); recurring runner producing an occurrence
(schedule created, due today, runner not observed); real SMTP (a local sink was used); archive/restore of a group
(an archive-ready settled group was seeded but not archived); comments, attachments, search and the data-export ZIP
(backend only, no UI); physical-device touch and performance.

## Cleanup

Removed: the PostgreSQL container with its volume (`r2-rehearsal-pg`), the v0.2.0 worktree, the Java, SMTP sink,
static-server, Chrome and browser-harness daemon processes (by PID), two throwaway Docker images, and the temp
directory with the jar files, database snapshots and downloaded CSV. No production dump existed. Other containers
(`e03-*`, `spliteasy-postgres`, `agitated_franklin`) and the dev DB on 5433 were not touched.
