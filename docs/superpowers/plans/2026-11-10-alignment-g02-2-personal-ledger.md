# G02.2 — authorized non-group expenses (personal ledger)

Second serial PR of roadmap [G02](2026-10-19-splitwise-alignment.md). Depends on verified `dev`
integration of [G02.1](2026-10-26-alignment-g02.md) (PR108, merged `ebd10a2`, released as v0.2.0).
Branch `feat/alignment-g02-2-personal-ledger`; target `dev`. Preserve the
[participant ADR](../../adr/0001-immutable-financial-participants.md), the
[ledger contract](../../api/multi-currency-ledger.md) and every recorded financial fact.
Sequential TDD at the confirmed HTTP/PostgreSQL, rendered Angular and owned native-browser seams;
Ponytail. At most three active threads, Sol high maximum, no Astra.

This is an XL subsystem plan written before coding, as the roadmap requires. File ownership and the
V21 reservation come from the [parallel batch plan](2026-11-11-alignment-parallel-batches.md), Wave 6. It settles the
storage decision; the debt/payment view stays in G02.3.

## Scope

A personal expense is an expense between the owner and one saved friend, outside any group. It
splits with the existing strategies, nets into balances with the existing aggregation, and appears
in the friend's existing per-currency detail. It is not a new money model.

Out of scope: friend-only settlement (G02.3), cross-group atomic settlement (B02), multi-payer
(E03), payment correction (B01), personal-scope search/export (P01/H05).

## Storage decision

**Reuse the group ledger machinery with a pair-scoped hidden ledger.** A personal ledger is a real
`groups` row marked `kind = 'PERSONAL'`, with exactly the two accounts as members and participants,
created on demand for an unordered account pair.

Why, despite the name being wrong: `expenses.group_id` is `NOT NULL` and every financial path —
balance aggregation, `ObligationProjectionService`, `PairwiseBalanceService`, dashboard, expense
recovery (V16), revisions, retry-safe creates (V12), currency partitioning (V15), the group write
lock — is keyed on it. A parallel `personal_expenses` table or a nullable `group_id` would fork or
re-derive all of that, which is exactly the "generalized ledger engine" the roadmap forbids. The
hidden ledger inherits all of it unchanged, including correctness properties already verified.

The cost is that personal ledgers must be excluded wherever groups surface, and group routes must
refuse them. That is a bounded set of filters, enumerated below, and each one gets a test.
Record this trade-off in the evidence file's Agents.md delta (the integrator applies it) so the
name is never mistaken for a user-visible group.

Rejected alternatives: nullable `expenses.group_id` (touches every financial query and index, and
weakens a NOT NULL invariant that currently guarantees scope); separate personal tables
(duplicates split, projection, recovery and currency logic — two code paths for one calculation).

### V21 (additive)

- `groups.kind VARCHAR(16) NOT NULL DEFAULT 'GROUP' CHECK (kind IN ('GROUP','PERSONAL'))`.
  Existing rows stay `GROUP`; no money, membership, participant or history row changes.
- `personal_ledgers(account_low UUID, account_high UUID, group_id UUID NOT NULL UNIQUE REFERENCES groups(id) ON DELETE CASCADE, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (account_low, account_high), CHECK (account_low < account_high))`
  with FKs to `users` and an index on `account_high`. UUID ordering makes one ledger per pair
  regardless of who creates it; the unique `group_id` forbids two pairs sharing a ledger.
- Partial index `idx_groups_kind_personal ON groups(id) WHERE kind = 'PERSONAL'`.

Creation races resolve with `INSERT ... ON CONFLICT DO NOTHING` on `personal_ledgers` followed by a
re-read, the V9/V12 pattern — never catch a uniqueness error and query an aborted transaction.

## Exclusions and refusals (each one a test)

- `GET /api/groups`, `GET /api/dashboard` groups/cards, group pickers: exclude `kind='PERSONAL'`.
  Their money still counts in dashboard per-currency totals and in friend detail.
- `GET/PATCH/DELETE /api/groups/{id}`, members add/remove, invitations, group participants,
  archive/unarchive/restore (G03): refuse a personal ledger with 404, so it is not addressable as a
  group and its existence is not revealed.
- Saved-friend removal and group access loss never delete a personal ledger or its money.
- A personal ledger has no creator-delete path in this PR; closing it belongs with A04 retention.

## Access and authorization

Authorization is participant capability plus membership in the pair ledger — never a group-guard
bypass. Reuse `MembershipGuard` (403 before 404) and `GroupWriteGuard` for the write lock, so
personal writes serialize exactly like group writes and inherit the historical-participant rules.

Create or resolve the ledger only when: the requester is authenticated (A03), the target is the
requester's own saved friend (G02.1 directed bookmark, owner-only), and the target is a registered
active account. An unclaimed guest is rejected with claim-first guidance; self is rejected,
including owned aliases. The friend bookmark authorizes *creating* the ledger; it never authorizes
reading someone else's ledger. Removing the bookmark does not revoke ledger access, because the
money is jointly owned — state that explicitly in the remove warning.

Both accounts are full members of the ledger, so either can add, edit and delete entries, matching
the flat group policy. Neither can remove the other.

## Wire contract

Authenticated, under `/api`. New routes require `X-Participant-Version: 1`; money follows
`X-Ledger-Version: 2` with explicit `currency` and `amountMinor`. Reuse `CreateExpenseRequest` and
`ExpenseResponse` unchanged — including `operationId`, `expectedVersion`, split intent and the
recovery fields — so retry safety, versions and history need no new contract.

- `GET /friends/{friendId}/expenses` → `ExpenseSummary[]`, newest first
- `POST /friends/{friendId}/expenses` → 201 `ExpenseResponse` (keyed replay per V12)
- `GET/PUT/DELETE /friends/{friendId}/expenses/{expenseId}` → detail / full-replace / 204 recoverable
- `GET /friends/{friendId}/expenses/deleted`, `POST .../{expenseId}/restore` (H01 semantics)

Participants are limited to the two accounts: a `paidByUserId` or split participant outside the
pair is 400 before any write. `splitType` and bounds validation are the existing strategy path.

Existing group routes gain nothing. `FriendDetail` keeps its shape; its `sharedGroups` continues to
list real groups, and personal money appears in its per-currency `balances` and in a new
`personalBalances: FriendBalance[]` so a reader can tell the two scopes apart.

## Frontend

`/friends/:id` gains a personal-expense list and an add/edit form reusing the existing expense
modal with the participant set fixed to the pair. Reuse `_tokens.scss`, `.dc-*`, `.dc-amount` and
`app-money`; no new money formatting. Personal entries are visually distinct from group entries and
never implied to be in a group.

Keep the established ownership rules: route and `SessionService` boundaries cancel owned callbacks
and clear drafts; foreground revalidation coalesces; failures are actionable and never render as
zero; uncertain writes keep their operation identity and offer explicit review instead of
auto-retrying. Account-owned, tab-isolated draft persistence follows E02 — a reload never dispatches.

## Acceptance

Sequential HTTP red/green: create in a pair ledger and see it in both accounts' friend detail;
per-currency separation (USD and JPY entries never combine); split intent round-trips through edit;
keyed replay returns the original 201 and changed intent is 409; stale `expectedVersion` is 409;
delete/restore moves balances once; a third account gets 403 on every route; outsider, guest, self
and unsaved-target creation is refused; concurrent first-write race creates exactly one ledger for
the pair; a personal ledger is 404 on every group route and absent from `/api/groups` and the
dashboard group list while its money still reaches dashboard totals; removing the saved friend
leaves the ledger and its money intact.

Sequential rendered red/green: list and empty states; add/edit/delete with running totals; an
unavailable read is not zero; A→B→A friend switches and session resets cancel stale reads and
writes; uncertain save offers review rather than retrying.

Then full backend `spotless:check verify`, frontend tests, Prettier, production build, OpenAPI
official-schema validation, deployment scripts and lints; a packaged populated V20→V21 rehearsal
and an owned native-browser pass at 1280/390 with zero page errors or overflow. Two independent
review axes against the pinned base.

## Rollout

V21 is additive and leaves existing installations semantically unchanged. Drain old backends before
personal writes: an old binary would list a personal ledger as an ordinary group and expose it on
group routes. Rollback keeps the schema; after personal expenses exist, use a forward fix. Deploy
G03's V19 and B01's V20 before or with V21, never out of order.

PR to `dev` only — no merge, no main promotion, no deployment. G02.3 waits for verified `dev`
integration of this slice.
