package com.spliteasy.service.split;

import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes per-participant shares for one {@link SplitType}. Each implementation is
 * self-contained (owns its own validation) and unit-testable without the service or DB.
 * The service selects the implementation by {@link #type()} — no split-type branching.
 */
public interface SplitStrategy {

    SplitType type();

    /** Per-participant shares, summing exactly to {@code ctx.totalCents()}. Throws on invalid input. */
    List<Share> split(SplitContext ctx);

    // --- shared rounding helpers (payer-absorbs-remainder), used by EQUAL and PERCENTAGE ---

    static void requirePositive(long totalCents) {
        if (totalCents <= 0) {
            throw new BadRequestException("Expense amount must be a positive number of cents");
        }
    }

    /**
     * Adds the leftover cents to the payer (or the first participant in id-sorted order
     * when the payer isn't a participant), then returns shares in that sorted order.
     */
    static List<Share> absorbRemainder(Map<UUID, Long> shares, List<UUID> sortedIds, UUID payerId, long remainder) {
        UUID absorber = sortedIds.contains(payerId) ? payerId : sortedIds.get(0);
        shares.merge(absorber, remainder, Long::sum);
        List<Share> result = new ArrayList<>(sortedIds.size());
        for (UUID id : sortedIds) {
            result.add(new Share(id, shares.get(id)));
        }
        return result;
    }
}
