package com.spliteasy.recurrence;

import com.spliteasy.dto.expense.CreateExpenseRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduleResponse(
    UUID id,
    UUID groupId,
    UUID createdBy,
    CreateExpenseRequest template,
    Frequency frequency,
    LocalDate anchorDate,
    String timeZone,
    LocalDate nextDate,
    long version,
    String status,
    String reviewReason,
    Instant createdAt,
    Instant cancelledAt) {}
