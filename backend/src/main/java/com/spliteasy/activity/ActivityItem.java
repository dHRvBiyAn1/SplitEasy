package com.spliteasy.activity;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** One committed event; {@code groupName} is null for a personal ledger. */
public record ActivityItem(
    UUID id,
    long seq,
    UUID groupId,
    String groupName,
    String kind,
    UUID subjectId,
    JsonNode actor,
    JsonNode summary,
    Instant occurredAt) {}
