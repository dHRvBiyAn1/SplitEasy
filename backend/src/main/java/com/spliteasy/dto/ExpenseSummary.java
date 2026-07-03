package com.spliteasy.dto;

import java.time.Instant;
import java.util.UUID;

public record ExpenseSummary(
        UUID id,
        String description,
        long amountCents,
        UserSummary paidBy,
        long participantCount,
        Instant createdAt) {}
