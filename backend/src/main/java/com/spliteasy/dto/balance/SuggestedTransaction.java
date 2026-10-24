package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.LedgerCurrency;

public record SuggestedTransaction(
    UserSummary from, UserSummary to, long amountMinor, LedgerCurrency currency) {
  public SuggestedTransaction(UserSummary from, UserSummary to, long amountMinor) {
    this(from, to, amountMinor, LedgerCurrency.USD);
  }

  @JsonIgnore
  public long amountCents() {
    return amountMinor;
  }

  @JsonProperty("amountCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? amountMinor : null;
  }
}
