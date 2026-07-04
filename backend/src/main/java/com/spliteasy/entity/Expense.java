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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    private Group group;

    @Column(nullable = false)
    private String description;

    /** Total amount in integer cents; always positive. */
    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paid_by", nullable = false)
    private User paidBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseParticipant> participants = new ArrayList<>();

    protected Expense() {
        // JPA
    }

    public Expense(Group group, String description, long amountCents, User paidBy) {
        this.group = group;
        this.description = description;
        this.amountCents = amountCents;
        this.paidBy = paidBy;
        this.createdAt = Instant.now();
    }

    /** Adds a participant share and keeps both sides of the relationship consistent. */
    public ExpenseParticipant addParticipant(User user, long shareCents) {
        ExpenseParticipant participant = new ExpenseParticipant(this, user, shareCents);
        participants.add(participant);
        return participant;
    }

    public UUID getId() {
        return id;
    }

    public Group getGroup() {
        return group;
    }

    public String getDescription() {
        return description;
    }

    public long getAmountCents() {
        return amountCents;
    }

    public User getPaidBy() {
        return paidBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ExpenseParticipant> getParticipants() {
        return participants;
    }
}
