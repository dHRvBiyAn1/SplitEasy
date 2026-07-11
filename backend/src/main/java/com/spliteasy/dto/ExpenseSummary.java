package com.spliteasy.dto;

import com.spliteasy.entity.ExpenseCategory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param viewerDeltaCents the requesting member's net for this expense: positive = they lent
 *     (paid more than their share), negative = they borrowed (owe their share), 0 = not involved.
 */
public record ExpenseSummary(
        UUID id,
        String description,
        long amountCents,
        UserSummary paidBy,
        long participantCount,
        ExpenseCategory category,
        LocalDate spentOn,
        long viewerDeltaCents,
        Instant createdAt) {}
