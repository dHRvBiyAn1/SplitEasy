package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.UuidGenerator;

/** A direct money transfer (settle-up) from one group member to another. */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
@Table(name = "payments")
public class Payment {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false, updatable = false)
  private Group group;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  // Correctable: B01 lets a member fix the wrong payer without rewriting history.
  @JoinColumn(name = "payer_id", nullable = false)
  private Participant payer;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payee_id", nullable = false)
  private Participant payee;

  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  @Column(nullable = false, length = 3)
  private LedgerCurrency currency = LedgerCurrency.USD;

  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "recorded_by", updatable = false)
  private UUID recordedBy;

  /** Read-only view of {@link #recordedBy} so a response can name the recorder without a lookup. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recorded_by", insertable = false, updatable = false)
  private User recorder;

  @Column(name = "operation_id", updatable = false)
  private UUID operationId;

  /** The user-chosen financial date, distinct from the server's {@link #createdAt}. */
  @Column(name = "paid_on", nullable = false)
  private java.time.LocalDate paidOn;

  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PaymentMethod method = PaymentMethod.OTHER;

  @Column(length = 280)
  private String note;

  @jakarta.persistence.Version
  @Column(nullable = false)
  private long version;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  public Payment(Group group, Participant payer, Participant payee, long amountCents) {
    this.group = group;
    this.payer = payer;
    this.payee = payee;
    this.amountCents = amountCents;
    // PostgreSQL timestamptz keeps microseconds and rounds, so an unrounded Instant.now() would
    // make this create receipt disagree with the row it describes. Store what we return.
    this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    this.paidOn = java.time.LocalDate.ofInstant(this.createdAt, java.time.ZoneOffset.UTC);
  }
}
