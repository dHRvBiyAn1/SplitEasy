package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

public record MemberBalance(ParticipantSummary user, long netMinor, LedgerCurrency currency) {
  public MemberBalance(ParticipantSummary user, long netMinor) {
    this(user, netMinor, LedgerCurrency.USD);
  }

  public MemberBalance(com.spliteasy.dto.common.UserSummary user, long netMinor) {
    this(ParticipantSummary.from(user), netMinor, LedgerCurrency.USD);
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
