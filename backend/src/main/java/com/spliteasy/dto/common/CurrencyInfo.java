package com.spliteasy.dto.common;

import com.spliteasy.entity.LedgerCurrency;

public record CurrencyInfo(LedgerCurrency code, int minorUnitExponent) {}
