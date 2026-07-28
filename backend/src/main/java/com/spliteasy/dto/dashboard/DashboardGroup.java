package com.spliteasy.dto.dashboard;

import com.spliteasy.entity.GroupType;
import java.util.UUID;

/**
 * One group card on the dashboard, from the requesting user's perspective.
 * {@code youAreOwedCents - youOweCents == netCents}.
 */
public record DashboardGroup(
        UUID id,
        String name,
        GroupType type,
        long memberCount,
        long totalSpentCents,
        long youAreOwedCents,
        long youOweCents,
        long netCents,
        /** Group setting: settle-up suggests the fewest payments for this group. */
        boolean simplifyDebts) {}
