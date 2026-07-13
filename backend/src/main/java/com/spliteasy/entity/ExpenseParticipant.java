package com.spliteasy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.*;

import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "expense_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_expense_participants_expense_user",
                columnNames = {"expense_id", "user_id"}))
public class ExpenseParticipant {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false, updatable = false)
    private Expense expense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** What this participant owes for the expense, in integer cents. */
    @Column(name = "share_cents", nullable = false)
    private long shareCents;

    public ExpenseParticipant(Expense expense, User user, long shareCents) {
        this.expense = expense;
        this.user = user;
        this.shareCents = shareCents;
    }
}
