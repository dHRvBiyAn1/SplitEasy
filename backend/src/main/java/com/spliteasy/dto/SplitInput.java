package com.spliteasy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

/**
 * One participant's split value for UNEQUAL/PERCENTAGE expenses.
 * {@code value} is cents (UNEQUAL) or basis points, i.e. hundredths of a percent (PERCENTAGE).
 */
public record SplitInput(
        @NotNull UUID userId,
        @PositiveOrZero long value) {}
