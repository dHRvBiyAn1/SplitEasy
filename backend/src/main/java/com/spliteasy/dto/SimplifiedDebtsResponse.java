package com.spliteasy.dto;

import java.util.List;
import java.util.UUID;

/**
 * A minimal set of transfers that settles the whole group, derived on demand from the current
 * balances. Not persisted — recompute from {@code BalanceService} on each request.
 */
public record SimplifiedDebtsResponse(UUID groupId, List<SuggestedTransaction> transactions) {}
