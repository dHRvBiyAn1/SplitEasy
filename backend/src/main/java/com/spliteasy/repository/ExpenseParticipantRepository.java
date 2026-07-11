package com.spliteasy.repository;

import com.spliteasy.entity.ExpenseParticipant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseParticipantRepository extends JpaRepository<ExpenseParticipant, UUID> {

    /**
     * Total share owed per user across all expenses in a group — one aggregate
     * query, not a per-expense loop.
     */
    @Query("""
            select p.user.id as userId, sum(p.shareCents) as totalCents
            from ExpenseParticipant p
            where p.expense.group.id = :groupId
            group by p.user.id
            """)
    List<UserAmount> sumOwedByGroup(@Param("groupId") UUID groupId);

    /**
     * One user's share of each expense across a set of groups — expenseId → shareCents. Used to
     * derive the viewer's per-expense "you lent / you borrowed" delta without an N+1.
     */
    @Query("""
            select p.expense.id as expenseId, p.shareCents as shareCents
            from ExpenseParticipant p
            where p.user.id = :userId and p.expense.group.id in :groupIds
            """)
    List<ExpenseShareView> findSharesByUserAndGroups(
            @Param("userId") UUID userId, @Param("groupIds") List<UUID> groupIds);

    /** expenseId → the requesting user's own share, in cents. */
    interface ExpenseShareView {
        UUID getExpenseId();

        long getShareCents();
    }

    /**
     * Others' shares in expenses the given user paid, grouped by the other participant — i.e. how
     * much each counterparty owes the user from the user's own expenses. Powers pairwise balances.
     */
    @Query("""
            select p.user.id as userId, sum(p.shareCents) as totalCents
            from ExpenseParticipant p
            where p.expense.group.id = :groupId
              and p.expense.paidBy.id = :userId
              and p.user.id <> :userId
            group by p.user.id
            """)
    List<UserAmount> sumOwedToUser(@Param("groupId") UUID groupId, @Param("userId") UUID userId);

    /**
     * The given user's shares in expenses others paid, grouped by the payer — i.e. how much the
     * user owes each counterparty from their expenses. Powers pairwise balances.
     */
    @Query("""
            select p.expense.paidBy.id as userId, sum(p.shareCents) as totalCents
            from ExpenseParticipant p
            where p.expense.group.id = :groupId
              and p.user.id = :userId
              and p.expense.paidBy.id <> :userId
            group by p.expense.paidBy.id
            """)
    List<UserAmount> sumOwedByUser(@Param("groupId") UUID groupId, @Param("userId") UUID userId);
}
