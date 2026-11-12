package com.spliteasy.settlement;

import com.spliteasy.entity.LedgerCurrency;
import java.util.List;
import java.util.UUID;

public record SettlementResponse(
    UUID id, LedgerCurrency currency, List<Allocation> allocations, long netMinor) {}
