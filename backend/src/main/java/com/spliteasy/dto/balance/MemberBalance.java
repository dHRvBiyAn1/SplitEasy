package com.spliteasy.dto.balance;

import com.spliteasy.dto.common.UserSummary;

/**
 * A member's net position in a group, in integer cents.
 * Positive = the member is owed money; negative = the member owes money; 0 = settled.
 */
public record MemberBalance(UserSummary user, long netCents) {}
