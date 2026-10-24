package com.spliteasy.dto.dashboard;

import com.spliteasy.entity.LedgerCurrency;

public record CurrencyTotals(
    LedgerCurrency currency,
    long totalNetMinor,
    long owedMinor,
    int owedPeopleCount,
    long oweMinor,
    int owePeopleCount) {}
