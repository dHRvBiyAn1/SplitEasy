package com.spliteasy.settlement;

import com.spliteasy.entity.LedgerCurrency;
import java.util.List;
import java.util.UUID;

/** {@code netMinor} is positive when the friend owes the requester across the allocations. */
public record SettlementPreview(
    LedgerCurrency currency, List<Allocation> allocations, List<Skipped> skipped, long netMinor) {
  public record Skipped(UUID scopeId, String groupName, String reason) {}
}
