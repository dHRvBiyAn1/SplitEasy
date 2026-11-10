package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record ExpenseRevision(
    String action,
    UserSummary actor,
    Instant recordedAt,
    long version,
    UUID operationId,
    ExpenseResponse before,
    ExpenseResponse after) {}
