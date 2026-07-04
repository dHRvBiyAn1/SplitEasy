package com.spliteasy.repository;

import com.spliteasy.entity.Expense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    /**
     * Expense summaries for a group in one query — payer joined, participant count via a
     * correlated subquery (single SQL statement, so no N+1 across the list).
     */
    @Query("""
            select e.id as id,
                   e.description as description,
                   e.amountCents as amountCents,
                   e.createdAt as createdAt,
                   payer.id as payerId,
                   payer.displayName as payerDisplayName,
                   payer.email as payerEmail,
                   (select count(p.id) from ExpenseParticipant p where p.expense = e) as participantCount
            from Expense e
            join e.paidBy payer
            where e.group.id = :groupId
            order by e.createdAt desc
            """)
    List<ExpenseSummaryView> findSummariesByGroupId(@Param("groupId") UUID groupId);

    /**
     * A single expense with payer, group, and all participant shares (with their users)
     * eagerly loaded in one query — no N+1 when building the detail response.
     */
    @Query("""
            select distinct e from Expense e
            join fetch e.paidBy
            join fetch e.group
            left join fetch e.participants p
            join fetch p.user
            where e.id = :expenseId
            """)
    Optional<Expense> findByIdFetchAll(@Param("expenseId") UUID expenseId);

    /** Projection backing {@link #findSummariesByGroupId(UUID)}. */
    interface ExpenseSummaryView {
        UUID getId();

        String getDescription();

        long getAmountCents();

        java.time.Instant getCreatedAt();

        UUID getPayerId();

        String getPayerDisplayName();

        String getPayerEmail();

        long getParticipantCount();
    }
}
