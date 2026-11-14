package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.ExpenseRefund;
import com.spliteasy.entity.LedgerCurrency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    UUID expenseId,
    long amountMinor,
    LedgerCurrency currency,
    ParticipantSummary receivedBy,
    LocalDate refundedOn,
    String note,
    UserSummary recordedBy,
    Instant createdAt,
    long version,
    Instant deletedAt,
    List<RefundShare> participants) {

  @com.fasterxml.jackson.annotation.JsonProperty("amountCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? amountMinor : null;
  }

  public static RefundResponse from(ExpenseRefund refund) {
    return new RefundResponse(
        refund.getId(),
        refund.getExpense().getId(),
        refund.getAmountCents(),
        refund.getCurrency(),
        ParticipantSummary.from(refund.getReceivedBy()),
        refund.getRefundedOn(),
        refund.getNote(),
        refund.getRecorder() == null ? null : UserSummary.from(refund.getRecorder()),
        refund.getCreatedAt(),
        refund.getVersion(),
        refund.getDeletedAt(),
        refund.getParticipants().stream()
            .map(share -> RefundShare.from(share, refund.getCurrency()))
            .sorted(java.util.Comparator.comparing(share -> share.user().id()))
            .toList());
  }
}
