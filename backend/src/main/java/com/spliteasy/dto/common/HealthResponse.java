package com.spliteasy.dto.common;

import java.time.Instant;

public record HealthResponse(String status, String service, Instant timestamp) {}
