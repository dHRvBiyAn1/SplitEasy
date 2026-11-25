package com.spliteasy.splitrule;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SplitRuleResponse(
    UUID id,
    UUID groupId,
    String name,
    SplitType splitType,
    List<SplitInput> inputs,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
