# Expense history and recovery

H01 retains an expense's attributable financial revisions and allows members to recover deleted expenses while their group still exists. It does not recover payments, deleted groups, or expenses hard-deleted before H01. Group deletion still cascades through its expenses, shares and revisions. No permanent-retention or purge policy is introduced.

## Authorized commands and reads

All routes require an active A03 session and current group membership. Membership is checked before resource existence, including history and recovery; former financial participation never grants access. Financial commands acquire the existing group-row lock and recheck membership after waiting.

| Route | Result |
| --- | --- |
| `GET /api/groups/{g}/expenses/{e}/history` | Chronological `ExpenseRevision[]`, including deleted expenses |
| `GET /api/groups/{g}/expenses/deleted` | Deleted entries `{expense: ExpenseResponse, deletedAt}` newest first |
| `DELETE /api/groups/{g}/expenses/{e}?expectedVersion={v}&operationId={uuid}` | 204, marks deletion and advances the resource version |
| `POST /api/groups/{g}/expenses/{e}/restore` | Body `{expectedVersion, operationId?}`; 200 with current `ExpenseResponse` |

Restore always requires a nonnegative `expectedVersion`, including before the E02 compatibility deadline. DELETE retains E02's finite missing-version compatibility period. Versions are opaque: obtain them from the current response, never calculate the next value. DELETE does not return the deleted version; fetch the recovery list or history before restoring.

Non-USD history, recovery and restore require `X-Ledger-Version: 2`; legacy callers receive 409 upgrade guidance after authorization. Currency and minor-unit integers are preserved without conversion across all six C01 denominations. USD legacy aliases remain USD-only; non-USD aliases are null.

Restore requires the retained payer and every retained share participant to be active, including zero-share participants. Otherwise 409 instructs the caller to explicitly re-add former participants first. Stale versions and already-active restores return 409; missing resources return 404. Invalid, unauthorized and conflicting commands change neither financial rows nor history. Repeated restore commands cannot count an expense twice.

The optional operation UUID is recorded for attribution on accepted writes. Replacement, delete and restore are versioned commands, not operation-ID replay endpoints. Do not automatically retry them. If a response is lost, reload and review the current expense/recovery state before another explicit action.

## Revisions and snapshots

Each accepted creation, full replacement, deletion and restoration records one revision in the same transaction as its financial write:

- `action`: `CREATED`, `REPLACED`, `DELETED`, `RESTORED`, or the migration-only `IMPORTED` baseline.
- `actor`: a captured `UserSummary`, distinct from the financial payer; imported baselines have null actors.
- `recordedAt`: server timestamp of recording. Imported baselines use migration time, not an invented historical event time.
- `version` and optional `operationId` identify the accepted resource state and supplied operation.
- `before` and `after`: complete `ExpenseResponse` snapshots, including currency, exact minor units, payer, shares, original split inputs, spent date and original creation date. Creation/import has a null `before`; deletion retains its financial snapshot with the new resource version.

V16 imports existing expenses at their known versions, retaining financial payer and dates. The historical recorder and any edits that were never retained are unknown. Missing original non-equal split inputs use the existing honest UNEQUAL exact-share fallback; ratios are never invented. Array ordering is presentation, not part of financial identity. Snapshot creation timestamps can retain receipt precision while later reads reflect PostgreSQL timestamp precision.

E02 create-operation receipts remain immutable. Replaying a keyed POST after replacement, deletion or restoration returns the original 201 snapshot; it never edits or restores the live expense and adds no revision.

Ordinary expense list/detail and recent activity exclude deleted expenses. Every expense and share aggregate excludes them too, covering dashboard totals/People/settlements, group net/pairwise/raw/current obligations, simplification and membership/mode lifecycle checks. Retained shares become financial again only through an accepted restore; recorded payments are unchanged.

## Migration and rollback

Apply V16 after integrated A03 V14 and C01 V15. `expenses.deleted_at` retains shares; `expense_revisions` stores JSONB actor and financial snapshots keyed by `(expense_id, version)`. Revisions cascade when the expense's group is explicitly deleted. There is no purge job or new dependency.

Drain **all old backend readers and writers before enabling H01**, including USD-only traffic. Old binaries count retained deleted rows as active expenses and can silently rewrite retained history. Deploy the migration and compatible backend together, with no mixed old fleet. Keep the schema and retained data on rollback; use a compatible forward fix. Reverting to an old expense-reading binary is unsafe, and removing deletion markers is not a rollback strategy. Production promotion remains a separate authorization.
