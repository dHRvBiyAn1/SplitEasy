package com.spliteasy.dto.expense;

import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param participantUserIds EQUAL only: subset of members (null/empty = all members)
 * @param splitType null defaults to EQUAL (back-compat)
 * @param splits all non-EQUAL types: original per-participant values (see {@link SplitInput})
 * @param category null defaults to {@link ExpenseCategory#OTHER}
 * @param spentOn null defaults to today
 */
public record CreateExpenseRequest(
    @NotBlank @Size(max = 200) String description,
    @Positive @Max(1_000_000_000_000L) Long amountCents,
    @NotNull UUID paidByUserId,
    List<UUID> participantUserIds,
    SplitType splitType,
    @Valid List<@NotNull SplitInput> splits,
    ExpenseCategory category,
    LocalDate spentOn,
    UUID operationId,
    @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,
    @Positive @Max(1_000_000_000_000L) Long amountMinor,
    com.spliteasy.entity.LedgerCurrency currency) {

  @com.fasterxml.jackson.annotation.JsonIgnore
  @jakarta.validation.constraints.AssertTrue(
      message = "Provide currency with amountMinor, or USD-only amountCents")
  public boolean isMoneyValid() {
    return amountMinor != null
        ? currency != null && amountCents == null
        : amountCents != null
            && (currency == null || currency == com.spliteasy.entity.LedgerCurrency.USD);
  }

  public long resolvedAmountMinor() {
    return amountMinor != null ? amountMinor : amountCents;
  }

  public com.spliteasy.entity.LedgerCurrency resolvedCurrency() {
    return currency == null ? com.spliteasy.entity.LedgerCurrency.USD : currency;
  }

  public CreateExpenseRequest(
      String description,
      long amountCents,
      UUID paidByUserId,
      List<UUID> participantUserIds,
      SplitType splitType,
      List<SplitInput> splits,
      ExpenseCategory category,
      LocalDate spentOn,
      UUID operationId,
      Long expectedVersion) {
    this(
        description,
        amountCents,
        paidByUserId,
        participantUserIds,
        splitType,
        splits,
        category,
        spentOn,
        operationId,
        expectedVersion,
        null,
        null);
  }

  public CreateExpenseRequest(
      String description,
      long amountCents,
      UUID paidByUserId,
      List<UUID> participantUserIds,
      SplitType splitType,
      List<SplitInput> splits,
      ExpenseCategory category,
      LocalDate spentOn) {
    this(
        description,
        amountCents,
        paidByUserId,
        participantUserIds,
        splitType,
        splits,
        category,
        spentOn,
        null,
        null);
  }

  /** Convenience for equal splits (and existing callers): no explicit splitType/splits/category. */
  public CreateExpenseRequest(
      String description, long amountCents, UUID paidByUserId, List<UUID> participantUserIds) {
    this(description, amountCents, paidByUserId, participantUserIds, null, null, null, null);
  }

  /** Convenience for typed splits without category/date (existing callers). */
  public CreateExpenseRequest(
      String description,
      long amountCents,
      UUID paidByUserId,
      List<UUID> participantUserIds,
      SplitType splitType,
      List<SplitInput> splits) {
    this(description, amountCents, paidByUserId, participantUserIds, splitType, splits, null, null);
  }
}
