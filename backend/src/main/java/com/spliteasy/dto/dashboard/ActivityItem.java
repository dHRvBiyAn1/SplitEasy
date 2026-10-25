package com.spliteasy.dto.dashboard;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.ExpenseCategory;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry in the cross-group recent-activity feed, from the requesting user's perspective.
 *
 * @param kind EXPENSE or PAYMENT
 * @param actor who paid (expense payer, or payment payer)
 * @param counterparty PAYMENT only: who received; null for EXPENSE
 * @param viewerDeltaCents EXPENSE: positive = you lent, negative = you borrowed, 0 = not involved.
 *     PAYMENT: 0 (settlements render neutral).
 * @param category EXPENSE only; null for PAYMENT
 */
public record ActivityItem(
    UUID id,
    Kind kind,
    UUID groupId,
    String groupName,
    String description,
    UserSummary actor,
    UserSummary counterparty,
    long amountMinor,
    long viewerDeltaMinor,
    ExpenseCategory category,
    LocalDate date,
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

  public enum Kind {
    EXPENSE,
    PAYMENT
  }
}
