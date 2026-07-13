package com.spliteasy.dto.dashboard;

import com.spliteasy.dto.balance.PersonBalance;
import com.spliteasy.dto.balance.Settlement;

import java.util.List;

/**
 * Everything the dashboard needs in one call, from the requesting user's perspective.
 *
 * <p>{@code totalNetCents == owedCents - oweCents}. {@code owedCents} is the sum of every person
 * who owes you (across all groups) and {@code owedPeopleCount} how many; {@code oweCents} /
 * {@code owePeopleCount} the same for people you owe. {@code settlements} is the per-group,
 * per-counterparty breakdown that powers the settle-up modal; {@code people} is that same data
 * aggregated across groups.
 */
public record DashboardResponse(
        long totalNetCents,
        long owedCents,
        int owedPeopleCount,
        long oweCents,
        int owePeopleCount,
        int groupCount,
        List<DashboardGroup> groups,
        List<PersonBalance> people,
        List<Settlement> settlements,
        List<ActivityItem> activity) {}
