package com.spliteasy.reminder;

import com.spliteasy.entity.LedgerCurrency;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReminderRequest(@NotNull UUID debtorId, @NotNull LedgerCurrency currency) {}
