package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.UserSummary;
import java.time.Instant;
import java.util.UUID;

/**
 * One attributable change to a settlement or refund. An imported baseline has a null actor — the
 * recorder of a pre-B01 payment is genuinely unknown and is never invented.
 */
public record LedgerRevision<T>(
    String action,
    UserSummary actor,
    Instant recordedAt,
    long version,
    UUID operationId,
    T before,
    T after) {}
