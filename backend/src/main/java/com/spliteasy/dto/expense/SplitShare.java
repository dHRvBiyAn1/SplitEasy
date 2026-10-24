package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.LedgerCurrency;

public record SplitShare(UserSummary user, long shareMinor, LedgerCurrency currency) {
  @com.fasterxml.jackson.annotation.JsonIgnore
  public long shareCents() {
    return shareMinor;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("shareCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? shareMinor : null;
  }
}
