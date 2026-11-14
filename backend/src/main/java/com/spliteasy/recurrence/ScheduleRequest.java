package com.spliteasy.recurrence;

import com.spliteasy.dto.expense.CreateExpenseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param template the expense each occurrence creates; its date and operation ID are ignored
 * @param anchorDate first occurrence, a local date in {@code timeZone} (IANA)
 * @param expectedVersion required on PUT only
 * @param operationId optional create retry key: same key and intent replays the original schedule
 */
public record ScheduleRequest(
    @NotNull @Valid CreateExpenseRequest template,
    @NotNull Frequency frequency,
    @NotNull LocalDate anchorDate,
    @NotBlank String timeZone,
    @PositiveOrZero Long expectedVersion,
    UUID operationId) {}
