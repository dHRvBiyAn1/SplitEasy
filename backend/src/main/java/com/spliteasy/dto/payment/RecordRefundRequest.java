package com.spliteasy.dto.payment;

import com.spliteasy.entity.LedgerCurrency;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A refund carries no split input of its own: it reverses the referenced expense's own allocation,
 * so an inventable allocation is deliberately not part of the contract. The currency is stated and
 * must match the expense's immutable currency.
 */
public record RecordRefundRequest(
    @NotNull @Positive @Max(1_000_000_000_000L) Long amountMinor,
    @NotNull LedgerCurrency currency,
    @NotNull LocalDate refundedOn,
    @Size(max = 280) String note,
    UUID operationId) {}
