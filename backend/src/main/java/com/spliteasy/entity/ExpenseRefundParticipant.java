package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** One participant's share of a refund — the amount their expense debt falls by. */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
@Table(
    name = "expense_refund_participants",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_expense_refund_participants_refund_participant",
            columnNames = {"refund_id", "participant_id"}))
public class ExpenseRefundParticipant {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "refund_id", nullable = false, updatable = false)
  private ExpenseRefund refund;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id", nullable = false, updatable = false)
  private Participant user;

  @Column(name = "share_cents", nullable = false, updatable = false)
  private long shareCents;

  public ExpenseRefundParticipant(ExpenseRefund refund, Participant user, long shareCents) {
    this.refund = refund;
    this.user = user;
    this.shareCents = shareCents;
  }
}
