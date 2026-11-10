package com.spliteasy.dto.dashboard;

import com.spliteasy.entity.LedgerCurrency;

public record GroupCurrencyTotals(
    LedgerCurrency currency,
    long totalSpentMinor,
    long youAreOwedMinor,
    long youOweMinor,
    long netMinor) {}
