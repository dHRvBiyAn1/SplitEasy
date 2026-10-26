package com.spliteasy.dto.common;

import com.spliteasy.entity.Participant;
import java.util.UUID;

/** Financial roles retain historical IDs; current ownership is explicit metadata. */
public record ParticipantSummary(
    UUID id, String email, String displayName, UUID canonicalParticipantId, UUID accountId) {
  public ParticipantSummary(UUID id, String email, String displayName) {
    this(id, email, displayName, id, id);
  }

  public static ParticipantSummary from(Participant p) {
    return new ParticipantSummary(
        p.getId(),
        p.getEmail(),
        p.getDisplayName(),
        p.getCanonicalParticipantId(),
        p.getAccountId());
  }

  public static ParticipantSummary from(UserSummary u) {
    return new ParticipantSummary(u.id(), u.email(), u.displayName(), u.id(), u.id());
  }
}
