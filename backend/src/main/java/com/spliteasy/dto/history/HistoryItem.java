package com.spliteasy.dto.history;

import com.spliteasy.entity.LedgerCurrency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One financial entry in a history page. {@code financialDate} is the user-chosen date (spent on,
 * paid on, refunded on); {@code updatedAt} is the entry's latest attributable revision time.
 */
public record HistoryItem(
    HistoryType type,
    UUID id,
    UUID groupId,
    String groupName,
    LocalDate financialDate,
    String description,
    LedgerCurrency currency,
    long amountMinor,
    Instant updatedAt) {}
