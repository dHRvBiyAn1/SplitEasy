package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;

import java.util.List;
import java.util.UUID;

/**
 * Input for a {@link SplitStrategy}. The service resolves group membership before
 * building this: for EQUAL it fills {@code participantIds}; for UNEQUAL/PERCENTAGE
 * it fills {@code splits}. A strategy reads whichever field it needs.
 */
public record SplitContext(
        long totalCents,
        UUID payerId,
        List<UUID> participantIds,
        List<SplitInput> splits) {

    /** EQUAL split over the given participants (no per-participant amounts). */
    public static SplitContext forEqual(long totalCents, UUID payerId, List<UUID> participantIds) {
        return new SplitContext(totalCents, payerId, participantIds, null);
    }

    /** UNEQUAL/PERCENTAGE split with explicit per-participant values (no participant-id list). */
    public static SplitContext forSplits(long totalCents, UUID payerId, List<SplitInput> splits) {
        return new SplitContext(totalCents, payerId, null, splits);
    }
}
