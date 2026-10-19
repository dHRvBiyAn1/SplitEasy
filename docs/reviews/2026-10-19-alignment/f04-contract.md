# F04 current API contract review

Baseline: `d0ea1b7a7711d9f4350c964a3c5dbbdc88c4a9ca` (`origin/dev`). This records the checked-in API behavior before F01/F03 integration. It describes current behavior only; later integration follow-ups remain open.

| Area | Source and evidence | Contract correction |
| --- | --- | --- |
| Payment retries | `RecordPaymentRequest`, `PaymentService`, V9 migration; `PaymentFlowIntegrationTest` | Optional UUID `operationId` is scoped to requester/group. Exact replay returns original `201`; changed payer/payee/amount returns `409`; omitted/null ID remains additive. OpenAPI now shows the field, example, replay and conflict behavior. `docs/api/payment-retries.md` already matched source. |
| Auth throttling | `AuthController`, `AuthRateLimiter`, `GlobalExceptionHandler`, `WebConfig`; `AuthAbuseIntegrationTest` | Login/register `429` responses include integer `Retry-After`, exposed through CORS. OpenAPI now declares the response/header; the auth abuse note names the `ErrorResponse` body. |
| Expense conflicts | `ExpenseEditConflictIntegrationTest`; `Expense` has no `@Version` | The coordinated overlapping PUT case can yield one success and one `409`. OpenAPI documents this narrow behavior and says there is no stale-GET version/ETag/If-Match protection. No stale-form guarantee is made. |
| Request nulls and integer bounds | Expense/group request DTOs and `ExpenseService`; baseline HTTP probe against local JAR + disposable PostgreSQL | `SplitInput.value` is required; both omission and explicit null return 400. Value range is 0–1,000,000,000,000. A valid UNEQUAL split with shares 100/0 returned 201. Fractional `amountCents: 100.5` returned 400. EQUAL expense requests with omitted or explicit-null `splitType`, `category`, `spentOn`, and `participantUserIds` returned 201 using EQUAL/OTHER/today/all members. PATCH `{}` and all-null settings returned 200 with unchanged fields. No production data was used. |
| Simplification and dashboard | `DebtSimplificationService`, `DashboardService`, `DashboardIntegrationTest` | Simplification is greedy and at most N−1 transfers, without a global-minimum promise. Dashboard people are netted across groups; headline owed/owe totals sum per-group counterparties first. Activity is a 15-item ledger snapshot sorted by expense spent date or payment recorded date, not event time (ordering verified from the query/service source; the retained dashboard test covers feed items/deltas, not sort order). |
| Error bodies | `GlobalExceptionHandler`, `SecurityConfig` | Application-handled domain/validation responses use `ErrorResponse`; security-filter and some framework parsing/path errors can differ. OpenAPI no longer assigns the Error schema to shared filter 401 or generic 400 responses. |
| Currency decision | Owner confirmed all existing amounts are USD on 2026-10-19 | The copied design, roadmap, and C01 backlog record USD as the pending legacy backfill. No schema/data migration is included; current wire fields remain `amountCents`, with no per-transaction currency field or conversion. |

F01/F03 integration must reconcile member-removal/history-write statuses, historical principal enumeration, transaction isolation claims, local profile restrictions, and pending-close behavior before this document is treated as final for the integrated implementation.

## Documentation verification

- PyYAML parsed `openapi.yaml` with duplicate-key rejection.
- All 127 internal OpenAPI references resolved.
- The document passed the official OpenAPI 3.1 JSON Schema (`https://spec.openapis.org/oas/3.1/schema/2025-09-15`) using installed `jsonschema`.
- The CSV and JSON backlog parsed; all 81 feature rows remain present, with one C01 row and its owner-confirmed USD decision metadata.
- `git diff --check` passed. The duplicate-key/ref/schema/backlog verifier is retained outside the repo for this run at `/workspace/issue-context/alignment-implementation/F04/verify_contract.py`.
- No application test suite was run for these documentation changes. HTTP observations above came from an owned local baseline JAR and disposable database; the probe cleaned up both and did not access production.
