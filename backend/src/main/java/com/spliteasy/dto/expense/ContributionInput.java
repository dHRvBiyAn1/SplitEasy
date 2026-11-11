package com.spliteasy.dto.expense;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** What one financial participant paid, in the expense currency's minor units. */
public record ContributionInput(
    @NotNull UUID userId, @Positive @Max(1_000_000_000_000L) long amountMinor) {}
