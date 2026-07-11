package com.spliteasy.repository;

import com.spliteasy.entity.Expense;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
                   e.category as category,
                   e.spentOn as spentOn,
                   e.createdAt as createdAt,
                   payer.id as payerId,
                   payer.displayName as payerDisplayName,
                   payer.email as payerEmail,
                   (select count(p.id) from ExpenseParticipant p where p.expense = e) as participantCount
            from Expense e
            join e.paidBy payer
            where e.group.id = :groupId
            order by e.spentOn desc, e.createdAt desc
            """)
    List<ExpenseSummaryView> findSummariesByGroupId(@Param("groupId") UUID groupId);

    /** Total of every expense in a group, in cents (0 if none). Drives the "total spent" card. */
    @Query("select coalesce(sum(e.amountCents), 0) from Expense e where e.group.id = :groupId")
    long sumAmountByGroup(@Param("groupId") UUID groupId);

    /**
     * Recent expenses across a set of groups (the user's groups) for the activity feed, newest
     * spent-date first, with payer + group name in one query. Caller passes a {@link Pageable}
     * to cap the row count.
     */
    @Query("""
            select e.id as id,
                   g.id as groupId,
                   g.name as groupName,
                   e.description as description,
                   e.amountCents as amountCents,
                   e.category as category,
                   e.spentOn as spentOn,
                   e.createdAt as createdAt,
                   payer.id as payerId,
                   payer.displayName as payerDisplayName,
                   payer.email as payerEmail
            from Expense e
            join e.paidBy payer
            join e.group g
            where g.id in :groupIds
            order by e.spentOn desc, e.createdAt desc
            """)
    List<ActivityExpenseView> findRecentByGroupIds(@Param("groupIds") List<UUID> groupIds, Pageable pageable);

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

    /**
     * Total amount paid per payer across all expenses in a group — one aggregate
     * query, not a per-expense loop.
     */
    @Query("""
            select e.paidBy.id as userId, sum(e.amountCents) as totalCents
            from Expense e
            where e.group.id = :groupId
            group by e.paidBy.id
            """)
    List<UserAmount> sumPaidByGroup(@Param("groupId") UUID groupId);

    /** Projection backing {@link #findSummariesByGroupId(UUID)}. */
    interface ExpenseSummaryView {
        UUID getId();

        String getDescription();

        long getAmountCents();

        com.spliteasy.entity.ExpenseCategory getCategory();

        java.time.LocalDate getSpentOn();

        java.time.Instant getCreatedAt();

        UUID getPayerId();

        String getPayerDisplayName();

        String getPayerEmail();

        long getParticipantCount();
    }

    /** Projection backing {@link #findRecentByGroupIds(List, Pageable)}. */
    interface ActivityExpenseView {
        UUID getId();

        UUID getGroupId();

        String getGroupName();

        String getDescription();

        long getAmountCents();

        com.spliteasy.entity.ExpenseCategory getCategory();

        java.time.LocalDate getSpentOn();

        java.time.Instant getCreatedAt();

        UUID getPayerId();

        String getPayerDisplayName();

        String getPayerEmail();
    }
}
