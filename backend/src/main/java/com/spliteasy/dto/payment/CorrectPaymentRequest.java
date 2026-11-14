package com.spliteasy.dto.payment;

import com.spliteasy.entity.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A full replacement of a recorded settlement's correctable facts. The currency is immutable, like
 * an expense's, and {@code expectedVersion} is required: payments have never carried a version, so
 * there is no legacy writer to grandfather.
 */
public record CorrectPaymentRequest(
    @NotNull UUID payerUserId,
    @NotNull UUID payeeUserId,
    @NotNull @Positive @Max(1_000_000_000_000L) Long amountMinor,
    com.spliteasy.entity.LedgerCurrency currency,
    @NotNull LocalDate paidOn,
    PaymentMethod method,
    @Size(max = 280) String note,
    @NotNull @PositiveOrZero Long expectedVersion,
    UUID operationId) {

  public PaymentMethod resolvedMethod() {
    return method == null ? PaymentMethod.OTHER : method;
  }
}
