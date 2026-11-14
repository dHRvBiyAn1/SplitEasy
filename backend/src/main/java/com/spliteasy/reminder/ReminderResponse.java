package com.spliteasy.reminder;

import com.spliteasy.entity.LedgerCurrency;
import java.util.UUID;

public record ReminderResponse(UUID debtorId, LedgerCurrency currency, long amountMinor) {}
