package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/** Money one financial participant actually paid towards an expense (V23). */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "expense_contributions")
public class ExpenseContribution {

  @Id
  @UuidGenerator
  @Column(nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "expense_id", nullable = false, updatable = false)
  private Expense expense;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "participant_id", nullable = false, updatable = false)
  private Participant participant;

  /** Positive minor units in the expense currency. */
  @Column(name = "amount_cents", nullable = false, updatable = false)
  private long amountCents;

  public ExpenseContribution(Expense expense, Participant participant, long amountCents) {
    this.expense = expense;
    this.participant = participant;
    this.amountCents = amountCents;
  }
}
