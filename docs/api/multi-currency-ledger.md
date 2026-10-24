# Denominated ledger

C01 stores currency identity with every expense and payment. Supported codes are USD, EUR, GBP and INR (2 decimal places), JPY (0), and KWD (3). `GET /api/currencies` returns this authenticated catalogue. No conversion, exchange rate or synthetic combined balance exists. Changing a profile preference only prefills untouched new drafts; it never changes history or an uncertain operation.

## Financial interface

New clients send `X-Ledger-Version: 2` on financial requests. Expense/payment writes use explicit `currency` and positive integer `amountMinor`, capped at 1,000,000,000,000 minor units. Fractional JSON tokens, missing denomination with minor units, unsupported codes, conflicting amount fields and out-of-range values return400. Expense PUT cannot change currency; create a separate expense instead. Version and former-participant guards still apply.

Responses carry `currency` with `amountMinor`, `shareMinor`, `viewerDeltaMinor` or `netMinor`. A zero share remains a participant. UNEQUAL and ADJUSTMENTS values use the transaction minor unit; percentages remain basis points (100%=10,000), and weights remain positive integers. The existing deterministic payer/UUID remainder rule conserves each expense exactly.

Every aggregate query, bilateral obligation key, greedy simplification partition, People row and settlement row includes currency. Dashboard `totals[]` contains `{currency,totalNetMinor,owedMinor,owedPeopleCount,oweMinor,owePeopleCount}`. Each dashboard group has `totals[]` containing `{currency,totalSpentMinor,youAreOwedMinor,youOweMinor,netMinor}`. These rows are authoritative. A member may have several group-balance rows. Settling USD leaves JPY/KWD intact; any nonzero currency or current-mode obligation blocks departure. Mode changes inspect obligations in every currency.

Transaction `*Cents` response aliases are USD-only and null for other denominations. Transitional dashboard/group scalar `*Cents` and `*Minor` aliases are null unless the complete scope is USD-only; they never return a partial USD headline from mixed data. Legacy `amountCents` writes remain USD-only. Clients without the version header receive409 upgrade guidance before seeing non-USD financial payloads or changing non-USD expenses. A legacy USD payment remains USD even in a mixed ledger.

Server responses exceeding ±9,007,199,254,740,991 minor units return409 rather than expose an unsafe JavaScript number. Per-transaction limits remain much smaller. Forms parse decimal text with integer arithmetic: the expense/payment amount must be exactly representable at the currency precision. Split inputs retain their disclosed rounding policy at the transaction precision; percentage rounding remains at2 decimal places. Grouping separators and scientific notation are not monetary amount input. Shared output derives the fraction from integer units and uses native Intl parts for presentation.

## Retry compatibility

Create operation snapshots remain immutable. Old stored expense responses are enriched only from their own historical USD cents: add currencyUSD and matching minor units, including stored shares. Original IDs, values, versions, nullable intent and timestamps survive later edit/deletion; no live expense is read and no snapshot is rewritten or resurrected. Intent equality narrowly normalizes missing currency toUSD and the old amount alias while retaining every other original field. Changed currency/minor amount conflicts. Payment replay also includes denomination.

Account-owned, tab-isolated pre-C01 browser drafts normalize missing currency toUSD, independently of current profile preferences. Old amount/net aliases become equivalent minor-unit fields while keeping the same operation ID and all other intent. Object-key ordering is immaterial; every key and array position remains part of intent equality. Unsupported/conflicting currencies block recovery. Restoration never dispatches automatically; uncertain intent still requires deliberate original retry or acknowledged abandonment.

## Migration and release gate

V15 follows A03 V14 (or both ship in that order in one release). The owner confirmed all historical money is USD. V15 adds non-null currency columns/defaultUSD and six-code constraints, extends the preference constraint, and preserves every existing integer. Physical `amount_cents`/`share_cents` names remain authoritative minor-unit storage; participants inherit expense currency. Avoid a second synchronized amount column.

Before non-USD writes are enabled:

1. Back up and rehearse populated V13→V14→V15 with original USD expenses, shares, payments, preferences and create receipts.
2. Stop/drain every old backend and financial writer. Verify the new binary/schema on all instances. Old binaries' aggregate queries cannot read mixed denominations safely; additive defaults do not make a mixed fleet safe.
3. Deploy the new client, verify its header and explicit amounts, and exercise USD+JPY separation, KWD precision, one-currency settlement, immutable expense currency, departure, retries and preference changes.
4. Retain compatibility aliases until deployed-client verification supports a separately reviewed retirement release. C01 does not retire them.

After any non-USD money is stored, use a forward fix. An old binary is an unsafe rollback even though it can open the additive schema. Keep V15 applied. A reviewed pre-cutover restore must reconcile every subsequent financial write; dropping currency columns or deleting rows to force settlement is not a rollback. A03 and earlier version/enum/auth rollback limits also remain applicable. This change performs no production deployment or external setup.
