# Splitwise alignment — remaining delivery plan (10 November 2026)

Sequencing plan for the work packages that are still open. It does **not** restate
package contracts: each package's scope, acceptance and model selection stay in the
[alignment roadmap](2026-10-19-splitwise-alignment.md), and the
[81-feature delivery map](2026-10-19-splitwise-alignment-backlog.csv) stays the
traceability source. This document adds only what the roadmap does not carry:
batch composition for the remainder, the production release gate, and when each
deferred decision must be answered.

Global constraints, dependency/ownership rules, delivery workflow, verification
commands, migration policy and release gates are unchanged and are not repeated here.
Read them in the roadmap before starting any batch.

## Status update (11 November 2026)

R1 is done: PR110 released `dev` `ebd10a2` to `main` `9316f6e`, tagged `v0.2.0`, with V10→V18
applied and live checks passed. SMTP stays deferred by owner decision. Batch 5 progress: G03 merged
(PR111, V19) and B01 merged (PR112, V20) to `dev`, both unreleased. G02.2 is planned (V21).
**The batch table below is superseded** by the
[parallel batch plan](2026-11-11-alignment-parallel-batches.md), which assigns file ownership and
migration numbers so three agents can work at once.

## Verified state

Measured on `dev` at `ebd10a2` on 10 November 2026, Docker available, Node 22.17.1:

- backend `./mvnw test` — 347 tests, 43 classes, 0 failures/errors/skips
- frontend `ng test --watch=false` — 369 tests, 52 files, 0 failures
- working tree clean; migrations V1→V18 apply from empty against PostgreSQL 16
- production build warns at 552 kB initial JS against the 500 kB budget (nonblocking)

Delivered packages (13 of 37): F01, F02, F03, F04 (M0); A01, A02, A03, E01, E02,
C01, H06 (M1); H01, G01 (M2). G02 is partially delivered: its directory/detail
slice merged as PR108; personal-expense CRUD and friend debt/payment remain.

Open: 24 packages, of which 7 are conditional and unscheduled.

Two shipped packages are inert in production: A02 recovery and G01 email invitations
both require configured SMTP, which is deferred.

## R1 — release the merged work before adding more (do this first)

`dev` is 102 commits ahead of `main`; `main` is still `aa287fb` (v0.1.0). Every
alignment package listed above is unreleased, and migrations V10→V18 are unapplied in
production. The order constraints are strict and compound: V12 before V13, V14 before
V15, and old binaries must drain before V15/V16/V17 writes. Each further batch adds
migrations to that single untested cutover, so the release gate comes before Batch 5,
not after M2.

- [x] Confirm combined suites, formatting, production build, deployment tests and lints on the exact `dev` head
- [x] Rehearse V10→V18 on a disposable copy restored from a production `pg_dump`, with reconciled row counts and per-currency totals
- [x] Verify the last released binary cannot write after cutover (drain, not overlap); record the forward-fix path, since post-V15/V16/V17 rollback is not schema-compatible
- [x] Create the `dev` → `main` release PR; merging it invokes production delivery and needs the then-current authorization
- [x] After delivery: public health, Angular deep links, one owned-account financial flow per currency, deployment job, release tag
- [ ] Configure Google Workspace SMTP (owned sender, TLS, egress) so A02 and G01 stop being dark; verify a real recovery mail and a real invitation

R1 is a gate, not an implementation package. No application behavior changes in it.

## Batch sequence

At most three concurrent workstreams, each a branch from verified `origin/dev` with a
PR to `dev`. A batch starts only when its dependencies are integrated in `dev`.
Branch naming follows the roadmap: `feat/alignment-<id>-<slug>`.

| Batch | Slots | Why these three together |
| --- | --- | --- |
| **5** | G02.2 personal-expense CRUD · G03 group lifecycle · B01 payment detail/correction/refunds | Three separate owners: personal ledger scope, group entity/settings, payment entity. G02.3 is held back because it reads the payment contract B01 is changing. |
| **6** | E03 multi-payer · H05 bounded history/export · G02.3 friend debt/payment view | E03 takes the ledger slot only after B01 integrates (shared projection is serialized). H05 owns all pagination/export DTOs; G02.3 consumes them and must not add its own paging. |
| **7** | B02 cross-group settlement · H03 durable activity + notifications · C02 locale | B02 needs B01 + G02 + F02 + C01, all integrated by then. H03 replaces latest-row inference with committed events. C02 follows C01's stable amount parser and never races it. |
| **8** | A04 account closure · H02 comments/attachments · H04 recurrence | A04 needs A03/H01/G01/H05. H02 needs the storage decision answered. H04 needs H03's outbox and preferences. |
| **R2** | — | Second release gate: M2 and M3 complete. Same checklist as R1, smaller migration set. |
| **9** | H07 PWA/offline · P01 search · P07 JSON backup | H07 is XL and takes the whole mobility slot; P01 and P07 are bounded reads over H05's contract. |
| **10** | P04 analytics · P05 saved split rules · then P02, P03 serially | P02 (OCR) and P03 (FX) each need a provider decision and run alone, not as a third concurrent Pro thread. |

Unscheduled and gated on a business or platform decision, never silently marked done:
A05 social sign-in, B03 real-money transfers, H08 native apps, H09 public API,
P06 bank import, P08 subscriptions, P09 card product.

## Expanding XL packages

G02 (remainder), E03, H07 and P02 are XL. Each needs a linked subsystem execution
plan with reviewable slices before coding, in the pattern already used by
[G01](2026-10-26-alignment-g01.md) and [C01](2026-10-24-alignment-c01.md). Do not open
an XL branch straight from this document.

## Decisions by batch

Answer each at its owning gate, not earlier.

| Needed before | Decision | Recommended default |
| --- | --- | --- |
| R1 | SMTP sender configuration | Google Workspace, already selected; configure it |
| Batch 7 | Push channel for H03 | In-app plus configured email first; defer push until a platform is chosen |
| Batch 7 | Second locale for C02 | Hindi as the first acceptance set, unless the intended audience says otherwise |
| Batch 8 | Private attachment storage for H02 | Existing hosting-compatible object storage, minimum permissions, lifecycle cleanup |
| Batch 8 | Retention/purge policy | State it before A04 or G03 enable any irreversible purge |
| Batch 10 | OCR and FX providers, retention and cost | Keep both out of scope until accuracy and cost are assessed |
| Unscheduled | Monetization, markets, native distribution, public OAuth | Keep current free, ad-free, unlimited-entry behavior; no provider assumption |

## Closing condition

The alignment programme is complete when every non-conditional package has merged to
`dev`, released through `main`, and its row in the delivery map reflects verified
behavior rather than a plan. Conditional rows stay conditional until a sponsor
approves their own design and acceptance contract.
