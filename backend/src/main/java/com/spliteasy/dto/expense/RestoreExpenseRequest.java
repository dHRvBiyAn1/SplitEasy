package com.spliteasy.dto.expense;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record RestoreExpenseRequest(
    @NotNull @PositiveOrZero Long expectedVersion, UUID operationId) {}
