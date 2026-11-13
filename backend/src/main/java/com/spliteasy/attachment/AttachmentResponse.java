package com.spliteasy.attachment;

import com.spliteasy.dto.common.UserSummary;
import java.time.Instant;
import java.util.UUID;

/** Metadata only; the bytes are served by the download route. */
public record AttachmentResponse(
    UUID id,
    UUID expenseId,
    UserSummary uploadedBy,
    String fileName,
    String contentType,
    long sizeBytes,
    String sha256,
    Instant createdAt) {}
