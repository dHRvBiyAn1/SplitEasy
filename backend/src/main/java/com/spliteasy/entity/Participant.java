package com.spliteasy.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Formula;

/** Immutable financial identity; ownership never grants account membership. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "participants")
public class Participant {
  @Id private UUID id;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "scope_group_id")
  private UUID scopeGroupId;

  @Formula("(select l.user_id from participant_account_links l where l.participant_id=id)")
  private UUID accountId;

  @Formula(
      "(select u.email from participant_account_links l join users u on u.id=l.user_id where l.participant_id=id)")
  private String email;

  @Formula(
      "(select u.display_name from participant_account_links l join users u on u.id=l.user_id where l.participant_id=id)")
  private String currentDisplayName;

  public String getDisplayName() {
    return scopeGroupId != null || currentDisplayName == null ? displayName : currentDisplayName;
  }

  public UUID getCanonicalParticipantId() {
    return accountId == null ? id : accountId;
  }
}
