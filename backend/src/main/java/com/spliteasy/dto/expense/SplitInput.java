package com.spliteasy.dto.expense;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Original typed input: exact cents (UNEQUAL), basis points (PERCENTAGE), positive integer units
 * (WEIGHTS), or signed cents added to equal allocation (ADJUSTMENTS). Strategies validate each
 * mode.
 */
public record SplitInput(
    @NotNull UUID userId, @Min(-1_000_000_000_000L) @Max(1_000_000_000_000L) long value) {}
