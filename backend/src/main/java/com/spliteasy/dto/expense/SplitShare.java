package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

public record SplitShare(ParticipantSummary user, long shareMinor, LedgerCurrency currency) {
  @com.fasterxml.jackson.annotation.JsonIgnore
  public long shareCents() {
    return shareMinor;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("shareCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? shareMinor : null;
  }
}
