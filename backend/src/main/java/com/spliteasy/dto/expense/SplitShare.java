package com.spliteasy.dto.expense;

import com.spliteasy.dto.common.UserSummary;

public record SplitShare(UserSummary user, long shareCents) {}
