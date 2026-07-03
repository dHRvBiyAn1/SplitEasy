package com.spliteasy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * @param amountCents        total in integer cents (positive) — keeps floating point off the wire
 * @param participantUserIds subset of the group's members; null/empty means "all members"
 */
public record CreateExpenseRequest(
        @NotBlank @Size(max = 200) String description,
        @Positive long amountCents,
        @NotNull UUID paidByUserId,
        List<UUID> participantUserIds) {}
