package com.spliteasy.dto.history;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Scope and bounds shared by the page and the export. Financial dates are inclusive calendar dates
 * with no timezone conversion; updated bounds are a half-open instant range [after, before).
 */
public record HistoryFilter(
    UUID groupId,
    UUID friendId,
    LocalDate from,
    LocalDate to,
    Instant updatedAfter,
    Instant updatedBefore) {}
