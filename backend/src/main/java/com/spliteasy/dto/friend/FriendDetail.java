package com.spliteasy.dto.friend;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FriendDetail(
    UUID id,
    String displayName,
    Instant createdAt,
    int sharedGroupCount,
    boolean hasOpenObligations,
    List<FriendBalance> balances,
    List<SharedGroup> sharedGroups) {
  public record SharedGroup(
      UUID id, String name, boolean simplifyDebts, List<FriendBalance> balances) {}
}
