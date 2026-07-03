package com.spliteasy.dto;

import java.util.UUID;

public record GroupSummary(UUID id, String name, long memberCount) {}
