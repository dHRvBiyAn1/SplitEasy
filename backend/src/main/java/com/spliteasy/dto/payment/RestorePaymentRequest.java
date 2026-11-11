package com.spliteasy.dto.payment;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record RestorePaymentRequest(
    @NotNull @PositiveOrZero Long expectedVersion, UUID operationId) {}
