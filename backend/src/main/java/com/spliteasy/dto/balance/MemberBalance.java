package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.LedgerCurrency;

public record MemberBalance(UserSummary user, long netMinor, LedgerCurrency currency) {
  public MemberBalance(UserSummary user, long netMinor) {
    this(user, netMinor, LedgerCurrency.USD);
  }

  @JsonIgnore
  public long netCents() {
    return netMinor;
  }

  @JsonProperty("netCents")
  public Long legacyNetCents() {
    return currency == LedgerCurrency.USD ? netMinor : null;
  }
}
