package com.spliteasy.dto.balance;

import com.spliteasy.dto.common.UserSummary;

/**
 * A counterparty's net position with the requesting user, aggregated across all shared groups.
 * Positive {@code netCents} = they owe you; negative = you owe them.
 */
public record PersonBalance(UserSummary user, long netCents) {}
