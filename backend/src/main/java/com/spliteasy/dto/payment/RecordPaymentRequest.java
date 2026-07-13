package com.spliteasy.dto.payment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record RecordPaymentRequest(
        @NotNull UUID payerUserId,
        @NotNull UUID payeeUserId,
        @Positive @Max(1_000_000_000_000L) long amountCents) {}
