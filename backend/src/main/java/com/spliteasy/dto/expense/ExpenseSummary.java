package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.ExpenseCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param viewerDeltaCents the requesting member's net for this expense: positive = they lent (paid
 *     more than their share), negative = they borrowed (owe their share), 0 = not involved.
 */
public record ExpenseSummary(
    UUID id,
    String description,
    long amountMinor,
    ParticipantSummary paidBy,
    long participantCount,
    ExpenseCategory category,
    LocalDate spentOn,
    long viewerDeltaMinor,
    Instant createdAt,
    long version,
    com.spliteasy.entity.LedgerCurrency currency) {
  @com.fasterxml.jackson.annotation.JsonIgnore
  public long amountCents() {
    return amountMinor;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("amountCents")
  public Long legacyAmountCents() {
    return currency == com.spliteasy.entity.LedgerCurrency.USD ? amountMinor : null;
  }

  @com.fasterxml.jackson.annotation.JsonIgnore
  public long viewerDeltaCents() {
    return viewerDeltaMinor;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("viewerDeltaCents")
  public Long legacyViewerdeltaCents() {
    return currency == com.spliteasy.entity.LedgerCurrency.USD ? viewerDeltaMinor : null;
  }
}
