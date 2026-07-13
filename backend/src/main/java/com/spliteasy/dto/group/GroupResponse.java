package com.spliteasy.dto.group;

import com.spliteasy.dto.common.UserSummary;

import com.spliteasy.entity.GroupType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GroupResponse(
        UUID id,
        String name,
        GroupType type,
        UserSummary createdBy,
        List<UserSummary> members,
        Instant createdAt) {}
