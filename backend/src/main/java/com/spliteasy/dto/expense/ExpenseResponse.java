package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.SplitType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
    UUID id,
    UUID groupId,
    String description,
    long amountMinor,
    SplitType splitType,
    ExpenseCategory category,
    LocalDate spentOn,
    ParticipantSummary paidBy,
    List<SplitShare> participants,
    List<UUID> participantUserIds,
    List<SplitInput> splits,
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
}
