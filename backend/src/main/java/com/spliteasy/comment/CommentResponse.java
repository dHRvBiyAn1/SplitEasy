package com.spliteasy.comment;

import com.spliteasy.dto.common.UserSummary;
import java.time.Instant;
import java.util.UUID;

public record CommentResponse(
    UUID id, UUID expenseId, UserSummary author, String body, Instant createdAt) {}
