package com.spliteasy.dto;

import com.spliteasy.entity.ExpenseCategory;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One entry in the cross-group recent-activity feed, from the requesting user's perspective.
 *
 * @param kind             EXPENSE or PAYMENT
 * @param actor            who paid (expense payer, or payment payer)
 * @param counterparty     PAYMENT only: who received; null for EXPENSE
 * @param viewerDeltaCents EXPENSE: positive = you lent, negative = you borrowed, 0 = not involved.
 *                         PAYMENT: 0 (settlements render neutral).
 * @param category         EXPENSE only; null for PAYMENT
 */
public record ActivityItem(
        UUID id,
        Kind kind,
        UUID groupId,
        String groupName,
        String description,
        UserSummary actor,
        UserSummary counterparty,
        long amountCents,
        long viewerDeltaCents,
        ExpenseCategory category,
        LocalDate date) {

    public enum Kind {
        EXPENSE,
        PAYMENT
    }
}
