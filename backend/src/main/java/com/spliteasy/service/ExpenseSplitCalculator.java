package com.spliteasy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure, dependency-free split math. Kept separate from {@link ExpenseService} so the
 * rounding behaviour can be unit-tested exhaustively without a database.
 *
 * <p>Rounding convention (AGENTS.md): every participant gets the integer floor of their
 * share; the leftover cents are absorbed by the payer when the payer is a participant,
 * otherwise by the first participant in id-sorted order. Returned shares always sum back
 * to exactly {@code totalCents} — no cent lost or invented.
 */
public final class ExpenseSplitCalculator {

    private ExpenseSplitCalculator() {
    }

    public record Share(UUID userId, long shareCents) {
    }

    /** Equal split: {@code total / n} each, payer absorbs the remainder. */
    public static List<Share> splitEqually(long totalCents, List<UUID> participantIds, UUID payerId) {
        requirePositive(totalCents);
        if (participantIds == null || participantIds.isEmpty()) {
            throw new IllegalArgumentException("An expense needs at least one participant");
        }
        List<UUID> ids = participantIds.stream().distinct().sorted().toList();
        long base = totalCents / ids.size();
        java.util.LinkedHashMap<UUID, Long> shares = new java.util.LinkedHashMap<>();
        long allocated = 0;
        for (UUID id : ids) {
            shares.put(id, base);
            allocated += base;
        }
        return absorbRemainder(shares, ids, payerId, totalCents - allocated);
    }

    /**
     * Percentage split. {@code basisPointsByUser} maps each participant to their share in
     * basis points (hundredths of a percent); they must sum to 10000. Each share is
     * {@code floor(total * bp / 10000)}; the payer absorbs the rounding remainder.
     */
    public static List<Share> splitByBasisPoints(long totalCents, Map<UUID, Long> basisPointsByUser, UUID payerId) {
        requirePositive(totalCents);
        if (basisPointsByUser == null || basisPointsByUser.isEmpty()) {
            throw new IllegalArgumentException("An expense needs at least one participant");
        }
        long totalBp = basisPointsByUser.values().stream().mapToLong(Long::longValue).sum();
        if (totalBp != 10_000) {
            throw new IllegalArgumentException(
                    "Percentages must add up to 100%% (got %.2f%%)".formatted(totalBp / 100.0));
        }
        List<UUID> ids = basisPointsByUser.keySet().stream().sorted().toList();
        java.util.LinkedHashMap<UUID, Long> shares = new java.util.LinkedHashMap<>();
        long allocated = 0;
        for (UUID id : ids) {
            long share = totalCents * basisPointsByUser.get(id) / 10_000; // floor; all non-negative
            shares.put(id, share);
            allocated += share;
        }
        return absorbRemainder(shares, ids, payerId, totalCents - allocated);
    }

    private static List<Share> absorbRemainder(
            Map<UUID, Long> shares, List<UUID> sortedIds, UUID payerId, long remainder) {
        UUID absorber = sortedIds.contains(payerId) ? payerId : sortedIds.get(0);
        shares.merge(absorber, remainder, Long::sum);
        List<Share> result = new ArrayList<>(sortedIds.size());
        for (UUID id : sortedIds) {
            result.add(new Share(id, shares.get(id)));
        }
        return result;
    }

    private static void requirePositive(long totalCents) {
        if (totalCents <= 0) {
            throw new IllegalArgumentException("Expense amount must be a positive number of cents");
        }
    }
}
