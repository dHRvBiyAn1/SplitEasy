package com.spliteasy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure, dependency-free equal-split math. Kept separate from {@link ExpenseService}
 * so the rounding behaviour can be unit-tested exhaustively without a database.
 *
 * <p>Rounding convention (matches AGENTS.md): every participant owes the integer
 * floor of {@code total / n}; the leftover cents ({@code total mod n}) are absorbed
 * by the payer when the payer is a participant, otherwise by the first participant
 * in a deterministic (id-sorted) order. The returned shares therefore always sum
 * back to exactly {@code totalCents} — no cent is ever lost or invented.
 */
public final class ExpenseSplitCalculator {

    private ExpenseSplitCalculator() {
    }

    public record Share(UUID userId, long shareCents) {
    }

    /**
     * @param totalCents     positive total to divide
     * @param participantIds who shares the cost (duplicates ignored); must be non-empty
     * @param payerId        who paid; absorbs remainder cents if among the participants
     * @return one share per distinct participant, summing exactly to {@code totalCents}
     * @throws IllegalArgumentException if the total is not positive or there are no participants
     */
    public static List<Share> splitEqually(long totalCents, List<UUID> participantIds, UUID payerId) {
        if (totalCents <= 0) {
            throw new IllegalArgumentException("Expense amount must be a positive number of cents");
        }
        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("An expense needs at least one participant");
        }

        // Deterministic order so the remainder always lands in the same place.
        List<UUID> ids = participantIds.stream().distinct().sorted().toList();
        int n = ids.size();

        long base = totalCents / n;
        long remainder = totalCents - base * n; // in [0, n-1]

        UUID absorber = ids.contains(payerId) ? payerId : ids.get(0);

        List<Share> shares = new ArrayList<>(n);
        for (UUID id : ids) {
            long share = id.equals(absorber) ? base + remainder : base;
            shares.add(new Share(id, share));
        }
        return shares;
    }
}
