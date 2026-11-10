package com.spliteasy.dto.friend;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FriendSummary(
    UUID id,
    String displayName,
    Instant createdAt,
    int sharedGroupCount,
    boolean hasOpenObligations,
    List<FriendBalance> balances) {}
