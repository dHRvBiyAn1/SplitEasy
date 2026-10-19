package com.spliteasy.dto.balance;

import com.spliteasy.dto.common.UserSummary;

/** Directed debt: from owes to. The endpoint defines raw or selected-mode interpretation. */
public record DirectObligation(UserSummary from, UserSummary to, long amountCents) {}
