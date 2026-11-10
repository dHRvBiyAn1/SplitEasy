package com.spliteasy.dto.auth;

import java.time.Instant;
import java.util.UUID;

public record DeviceSession(
    UUID id,
    String deviceLabel,
    Instant createdAt,
    Instant refreshedAt,
    Instant expiresAt,
    boolean current) {}
