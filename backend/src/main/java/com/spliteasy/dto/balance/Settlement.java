package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

public record Settlement(
    java.util.UUID groupId,
    String groupName,
    ParticipantSummary counterparty,
    long netMinor,
    LedgerCurrency currency) {
  public Settlement(
      java.util.UUID groupId, String groupName, ParticipantSummary counterparty, long netMinor) {
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
