package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.LedgerCurrency;

public record Settlement(
    java.util.UUID groupId,
    String groupName,
    UserSummary counterparty,
    long netMinor,
    LedgerCurrency currency) {
  public Settlement(
      java.util.UUID groupId, String groupName, UserSummary counterparty, long netMinor) {
    this(groupId, groupName, counterparty, netMinor, LedgerCurrency.USD);
  }

  @JsonIgnore
  public long netCents() {
    return netMinor;
  }

  @JsonProperty("netCents")
  public Long legacyCents() {
    return currency == LedgerCurrency.USD ? netMinor : null;
  }
}
