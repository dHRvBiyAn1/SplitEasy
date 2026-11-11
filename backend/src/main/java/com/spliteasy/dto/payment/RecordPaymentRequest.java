package com.spliteasy.dto.payment;

import com.spliteasy.entity.PaymentMethod;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record RecordPaymentRequest(
    @NotNull UUID payerUserId,
    @NotNull UUID payeeUserId,
    @Positive @Max(1_000_000_000_000L) Long amountCents,
    UUID operationId,
    @Positive @Max(1_000_000_000_000L) Long amountMinor,
    com.spliteasy.entity.LedgerCurrency currency,
    LocalDate paidOn,
    PaymentMethod method,
    @Size(max = 280) String note) {

  public RecordPaymentRequest(
      UUID payerUserId,
      UUID payeeUserId,
      Long amountCents,
      UUID operationId,
      Long amountMinor,
      com.spliteasy.entity.LedgerCurrency currency) {
    this(
        payerUserId,
        payeeUserId,
        amountCents,
        operationId,
        amountMinor,
        currency,
        null,
        null,
        null);
  }

  public RecordPaymentRequest(
      UUID payerUserId, UUID payeeUserId, long amountCents, UUID operationId) {
    this(payerUserId, payeeUserId, amountCents, operationId, null, null);
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  @jakarta.validation.constraints.AssertTrue(
      message = "Provide currency with amountMinor, or USD-only amountCents")
  public boolean isMoneyValid() {
    return amountMinor != null
        ? currency != null && amountCents == null
        : amountCents != null
            && (currency == null || currency == com.spliteasy.entity.LedgerCurrency.USD);
  }

  public long resolvedAmountMinor() {
    return amountMinor != null ? amountMinor : amountCents;
  }

  public com.spliteasy.entity.LedgerCurrency resolvedCurrency() {
    return currency == null ? com.spliteasy.entity.LedgerCurrency.USD : currency;
  }

  /** An omitted method is descriptive metadata, so it defaults rather than failing the write. */
  public PaymentMethod resolvedMethod() {
    return method == null ? PaymentMethod.OTHER : method;
  }

  public RecordPaymentRequest(UUID payerUserId, UUID payeeUserId, long amountCents) {
    this(payerUserId, payeeUserId, amountCents, null);
  }
}
