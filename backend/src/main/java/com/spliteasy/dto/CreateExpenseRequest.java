package com.spliteasy.dto;

import com.spliteasy.entity.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * @param participantUserIds EQUAL only: subset of members (null/empty = all members)
 * @param splitType          null defaults to EQUAL (back-compat)
 * @param splits             UNEQUAL/PERCENTAGE only: per-participant value (cents or basis points)
 */
public record CreateExpenseRequest(
        @NotBlank @Size(max = 200) String description,
        @Positive long amountCents,
        @NotNull UUID paidByUserId,
        List<UUID> participantUserIds,
        SplitType splitType,
        @Valid List<SplitInput> splits) {

    /** Convenience for equal splits (and existing callers): no explicit splitType/splits. */
    public CreateExpenseRequest(String description, long amountCents, UUID paidByUserId, List<UUID> participantUserIds) {
        this(description, amountCents, paidByUserId, participantUserIds, null, null);
    }
}
