package com.spliteasy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        UUID groupId,
        String description,
        long amountCents,
        UserSummary paidBy,
        List<SplitShare> participants,
        Instant createdAt) {}
