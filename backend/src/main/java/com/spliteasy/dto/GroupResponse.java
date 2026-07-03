package com.spliteasy.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GroupResponse(
        UUID id,
        String name,
        UserSummary createdBy,
        List<UserSummary> members,
        Instant createdAt) {}
