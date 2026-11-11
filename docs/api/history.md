# History and CSV export (H05)

`GET /api/groups/history` and `GET /api/groups/history/export` (literal path, so it wins over `/groups/{id}`).

- **Scope**: the caller's current, non-deleted groups. `groupId` narrows to one group (403 if the caller is not a member, checked first; deleted group 404). `friendId` narrows to entries both people take part in (404 if not a saved friend). Former membership never grants access.
- **Entries**: active expenses, settlements and refunds only; soft-deleted rows (and refunds of deleted expenses) are excluded. Amounts are `currency` + integer `amountMinor`.
- **Order and paging**: `(financialDate DESC, id DESC)`, keyset. `limit` default 50, max 100 (else 400). `nextCursor` is opaque; null on the last page. No gaps or duplicates at equal dates.
- **Filters**: `from`/`to` inclusive calendar dates (no timezone conversion); `updatedAfter` (inclusive) / `updatedBefore` (exclusive) use the latest revision time (creation time if none).
- **CSV**: streamed through a database cursor, never buffered. One row per participant per entry, columns `transaction_id,type,financial_date,group_name,description,currency,amount_minor,amount,participant_id,participant_name,paid_minor,owed_minor,settlement_minor,net_minor,net,last_changed_by`. `paid_minor` reads `expenses.paid_by`; a payer outside the split still gets a row. Refund rows are negative. Per currency, `net_minor` sums to zero; count each `transaction_id`'s `amount_minor` once. Text cells starting with `= + - @` tab or CR get a leading apostrophe.
- No schema change: existing group/date indexes serve the scope filter.
- **Personal ledgers**: a `groupId` naming the caller's personal ledger is 404. Their entries remain in account-wide and friend-scoped history with `groupName` null (CSV `group_name` empty).
