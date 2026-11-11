package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.Payment;
import com.spliteasy.entity.PaymentMethod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    ParticipantSummary payer,
    ParticipantSummary payee,
    long amountMinor,
    Instant createdAt,
    com.spliteasy.entity.LedgerCurrency currency,
    LocalDate paidOn,
    PaymentMethod method,
    String note,
    UserSummary recordedBy,
    long version,
    Instant deletedAt) {

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
        payment.getCurrency(),
        payment.getPaidOn(),
        payment.getMethod(),
        payment.getNote(),
        // A legacy unkeyed payment has no known recorder; it stays null rather than invented.
        payment.getRecorder() == null ? null : UserSummary.from(payment.getRecorder()),
        payment.getVersion(),
        payment.getDeletedAt());
  }
}
