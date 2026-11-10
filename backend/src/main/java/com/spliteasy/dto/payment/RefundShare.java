package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.ExpenseRefundParticipant;
import com.spliteasy.entity.LedgerCurrency;

/** How much one participant's expense debt falls because of a refund. */
public record RefundShare(ParticipantSummary user, long shareMinor, LedgerCurrency currency) {

  @com.fasterxml.jackson.annotation.JsonProperty("shareCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? shareMinor : null;
  }

  public static RefundShare from(ExpenseRefundParticipant share, LedgerCurrency currency) {
    return new RefundShare(
        ParticipantSummary.from(share.getUser()), share.getShareCents(), currency);
  }
}
