package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    UserSummary payer,
    UserSummary payee,
    long amountMinor,
    Instant createdAt,
    com.spliteasy.entity.LedgerCurrency currency) {

  @com.fasterxml.jackson.annotation.JsonIgnore
  public long amountCents() {
    return amountMinor;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("amountCents")
  public Long legacyCents() {
    return currency == com.spliteasy.entity.LedgerCurrency.USD ? amountMinor : null;
  }

  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        UserSummary.from(payment.getPayer()),
        UserSummary.from(payment.getPayee()),
        payment.getAmountCents(),
        payment.getCreatedAt(),
        payment.getCurrency());
  }
}
