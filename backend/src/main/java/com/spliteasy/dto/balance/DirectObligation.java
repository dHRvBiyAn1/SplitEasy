package com.spliteasy.dto.balance;

import com.spliteasy.dto.common.UserSummary;

/** Raw bilateral debt: from owes to; independent of simplified transfer suggestions. */
public record DirectObligation(UserSummary from, UserSummary to, long amountCents) {}
