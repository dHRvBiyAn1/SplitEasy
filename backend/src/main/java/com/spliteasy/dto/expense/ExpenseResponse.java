package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.UserSummary;

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
        long amountCents,
        SplitType splitType,
        ExpenseCategory category,
        LocalDate spentOn,
        UserSummary paidBy,
        List<SplitShare> participants,
        Instant createdAt) {}
