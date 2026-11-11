package com.spliteasy.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * Money returned out of one expense. The amount is positive, as every stored amount is; the reverse
 * sign lives in the reader, which subtracts the refund and its stored allocation from the ledger.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA only
@Table(name = "expense_refunds")
public class ExpenseRefund {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "expense_id", nullable = false, updatable = false)
  private Expense expense;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "group_id", nullable = false, updatable = false)
  private Group group;

  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  @Column(nullable = false, length = 3, updatable = false)
  private LedgerCurrency currency = LedgerCurrency.USD;

  @Column(name = "amount_cents", nullable = false, updatable = false)
  private long amountCents;

  /** Who got the money back: the expense's payer at the moment of the refund. */
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "received_by", nullable = false, updatable = false)
  private Participant receivedBy;

  @Column(name = "refunded_on", nullable = false, updatable = false)
  private LocalDate refundedOn;

  @Column(length = 280, updatable = false)
  private String note;

  @Column(name = "recorded_by", updatable = false)
  private UUID recordedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "recorded_by", insertable = false, updatable = false)
  private User recorder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @jakarta.persistence.Version
  @Column(nullable = false)
  private long version;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @OneToMany(mappedBy = "refund", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<ExpenseRefundParticipant> participants = new ArrayList<>();

  public ExpenseRefund(
      Expense expense,
      Participant receivedBy,
      long amountCents,
      LocalDate refundedOn,
      String note,
      UUID recordedBy) {
    this.expense = expense;
    this.group = expense.getGroup();
    this.currency = expense.getCurrency();
    this.receivedBy = receivedBy;
    this.amountCents = amountCents;
    this.refundedOn = refundedOn;
    this.note = note;
    this.recordedBy = recordedBy;
    // Storage-faithful, for the same reason as Payment: microsecond precision, no rounding drift.
    this.createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }
}
