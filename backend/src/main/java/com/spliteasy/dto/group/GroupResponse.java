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
    boolean simplifyDebts,
    Instant createdAt,
    List<com.spliteasy.dto.common.ParticipantSummary> participants,
    UUID viewerParticipantId,
    List<UUID> ownedParticipantIds,
    List<com.spliteasy.dto.common.ParticipantSummary> inactiveGuests) {
  public GroupResponse(
      UUID id,
      String name,
      GroupType type,
      UserSummary createdBy,
      List<UserSummary> members,
      boolean simplifyDebts,
      Instant createdAt) {
    this(id, name, type, createdBy, members, simplifyDebts, createdAt, null, null, null, null);
  }
}
