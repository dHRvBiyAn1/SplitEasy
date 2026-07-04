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
}
