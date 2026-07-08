package com.spliteasy.dto;

/**
 * One suggested settle-up transfer from the debt-simplification algorithm: {@code from} (a
 * debtor) pays {@code to} (a creditor) {@code amountCents}. Mirrors the {@code payer -> payee}
 * semantics of a recorded {@code Payment}, so acting on it drives both nets toward zero.
 */
public record SuggestedTransaction(UserSummary from, UserSummary to, long amountCents) {}
