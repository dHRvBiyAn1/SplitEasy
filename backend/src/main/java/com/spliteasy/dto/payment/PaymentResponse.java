package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    ParticipantSummary payer,
    ParticipantSummary payee,
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
        ParticipantSummary.from(payment.getPayer()),
        ParticipantSummary.from(payment.getPayee()),
        payment.getAmountCents(),
        payment.getCreatedAt(),
        payment.getCurrency());
  }
}
