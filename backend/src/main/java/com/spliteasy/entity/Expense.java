package com.spliteasy.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseCategory category;

    /** The date the money was actually spent (user-chosen); distinct from {@link #createdAt}. */
    @Column(name = "spent_on", nullable = false)
    private LocalDate spentOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseParticipant> participants = new ArrayList<>();

    protected Expense() {
        // JPA
    }

    public Expense(
            Group group, String description, long amountCents, User paidBy, SplitType splitType,
            ExpenseCategory category, LocalDate spentOn) {
        this.group = group;
        this.description = description;
        this.amountCents = amountCents;
        this.paidBy = paidBy;
        this.splitType = splitType;
        this.category = category;
        this.spentOn = spentOn;
        this.createdAt = Instant.now();
    }

    /** Back-compat convenience: no category/date → {@link ExpenseCategory#OTHER} spent today. */
    public Expense(Group group, String description, long amountCents, User paidBy, SplitType splitType) {
        this(group, description, amountCents, paidBy, splitType, ExpenseCategory.OTHER, LocalDate.now());
    }

    /** Adds a participant share and keeps both sides of the relationship consistent. */
    public ExpenseParticipant addParticipant(User user, long shareCents) {
        ExpenseParticipant participant = new ExpenseParticipant(this, user, shareCents);
        participants.add(participant);
        return participant;
    }

    /** Removes all participant shares (orphanRemoval deletes the rows) — used when re-splitting on edit. */
    public void clearParticipants() {
        participants.clear();
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setAmountCents(long amountCents) {
        this.amountCents = amountCents;
    }

    public void setPaidBy(User paidBy) {
        this.paidBy = paidBy;
    }

    public void setSplitType(SplitType splitType) {
        this.splitType = splitType;
    }

    public void setCategory(ExpenseCategory category) {
        this.category = category;
    }

    public void setSpentOn(LocalDate spentOn) {
        this.spentOn = spentOn;
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

    public SplitType getSplitType() {
        return splitType;
    }

    public ExpenseCategory getCategory() {
        return category;
    }

    public LocalDate getSpentOn() {
        return spentOn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<ExpenseParticipant> getParticipants() {
        return participants;
    }
}
