package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

public record DirectObligation(
    ParticipantSummary from, ParticipantSummary to, long amountMinor, LedgerCurrency currency) {
  public DirectObligation(ParticipantSummary from, ParticipantSummary to, long amountMinor) {
    this(from, to, amountMinor, LedgerCurrency.USD);
  }

  public DirectObligation(
      com.spliteasy.dto.common.UserSummary from,
      com.spliteasy.dto.common.UserSummary to,
      long amountMinor) {
    this(
        ParticipantSummary.from(from),
        ParticipantSummary.from(to),
        amountMinor,
        LedgerCurrency.USD);
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
