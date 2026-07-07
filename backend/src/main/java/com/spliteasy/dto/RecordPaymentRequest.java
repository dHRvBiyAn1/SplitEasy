package com.spliteasy.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record RecordPaymentRequest(
        @NotNull UUID payerUserId,
        @NotNull UUID payeeUserId,
        @Positive long amountCents) {}
