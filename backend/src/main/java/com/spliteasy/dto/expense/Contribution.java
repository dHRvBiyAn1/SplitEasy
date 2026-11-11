package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;

/** Persisted money one participant paid towards an expense; inherits the expense currency. */
public record Contribution(ParticipantSummary user, long amountMinor, LedgerCurrency currency) {}
