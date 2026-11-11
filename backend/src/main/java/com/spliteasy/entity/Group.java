package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

@Entity
@DynamicUpdate // Partial settings edits must not write stale values of omitted fields.
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
@Table(name = "groups")
public class Group {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GroupType type;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "created_by", nullable = false, updatable = false)
  private User createdBy;

  /**
   * Selects canonical net-matched or direct pair obligations (migration V8). Governs debt views,
   * settlement actions and departure eligibility without rewriting recorded history.
   */
  @Column(name = "simplify_debts", nullable = false)
  private boolean simplifyDebts = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Set while the group is read-only: reads and export keep working, new entries do not. */
  @Column(name = "archived_at")
  private Instant archivedAt;

  /** Recoverable deletion. Every ordinary read treats a marked group as gone (migration V19). */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  /** Opaque precondition for restore; advances on each archive/unarchive/delete/restore. */
  @Column(name = "lifecycle_version", nullable = false)
  private long lifecycleVersion;

  /** GROUP, or PERSONAL for a hidden pair ledger that group routes never expose (migration V21). */
  @Column(nullable = false, updatable = false)
  private String kind = "GROUP";

  public boolean isPersonal() {
    return "PERSONAL".equals(kind);
  }

  /** The hidden ledger behind one pair's non-group expenses; never listed as a group. */
  public static Group personalLedger(User creator) {
    Group ledger = new Group("Personal ledger", creator);
    ledger.kind = "PERSONAL";
    return ledger;
  }

  public boolean isArchived() {
    return archivedAt != null;
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  /** One place advances the precondition, so no lifecycle write can forget to. */
  private void advance() {
    this.lifecycleVersion++;
  }

  public void archive() {
    this.archivedAt = Instant.now();
    advance();
  }

  public void unarchive() {
    this.archivedAt = null;
    advance();
  }

  public void markDeleted() {
    this.deletedAt = Instant.now();
    advance();
  }

  /** Restores the group; an archived group stays archived until it is explicitly re-opened. */
  public void restore() {
    this.deletedAt = null;
    advance();
  }

  public Group(String name, GroupType type, User createdBy) {
    this.name = name;
    this.type = type;
    this.createdBy = createdBy;
    this.simplifyDebts = true;
    this.createdAt = Instant.now();
  }

  /** Back-compat convenience: untyped group defaults to {@link GroupType#OTHER}. */
  public Group(String name, User createdBy) {
    this(name, GroupType.OTHER, createdBy);
  }
}
