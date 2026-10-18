package com.spliteasy.dto.balance;

import java.util.List;
import java.util.UUID;

/**
 * Suggested greedy transfers that settle the whole group, derived on demand from the current
 * balances. Not persisted — recompute from {@code BalanceService} on each request.
 */
public record SimplifiedDebtsResponse(UUID groupId, List<SuggestedTransaction> transactions) {}
