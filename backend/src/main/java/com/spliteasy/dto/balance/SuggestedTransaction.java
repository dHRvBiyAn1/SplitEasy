package com.spliteasy.dto.balance;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

public record SuggestedTransaction(
    ParticipantSummary from, ParticipantSummary to, long amountMinor, LedgerCurrency currency) {
  public SuggestedTransaction(ParticipantSummary from, ParticipantSummary to, long amountMinor) {
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
