package com.spliteasy.dto;

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
 * @param splitType          null defaults to EQUAL (back-compat)
 * @param splits             UNEQUAL/PERCENTAGE only: per-participant value (cents or basis points)
 * @param category           null defaults to {@link ExpenseCategory#OTHER}
 * @param spentOn            null defaults to today
 */
public record CreateExpenseRequest(
        @NotBlank @Size(max = 200) String description,
        @Positive @Max(1_000_000_000_000L) long amountCents,
        @NotNull UUID paidByUserId,
        List<UUID> participantUserIds,
        SplitType splitType,
        @Valid List<SplitInput> splits,
        ExpenseCategory category,
        LocalDate spentOn) {

    /** Convenience for equal splits (and existing callers): no explicit splitType/splits/category. */
    public CreateExpenseRequest(String description, long amountCents, UUID paidByUserId, List<UUID> participantUserIds) {
        this(description, amountCents, paidByUserId, participantUserIds, null, null, null, null);
    }

    /** Convenience for typed splits without category/date (existing callers). */
    public CreateExpenseRequest(
            String description, long amountCents, UUID paidByUserId, List<UUID> participantUserIds,
            SplitType splitType, List<SplitInput> splits) {
        this(description, amountCents, paidByUserId, participantUserIds, splitType, splits, null, null);
    }
}
