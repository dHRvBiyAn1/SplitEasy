package com.spliteasy.repository;

import com.spliteasy.entity.Payment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Total paid out per payer across a group's settle-up payments (one aggregate query). */
    @Query("""
            select p.payer.id as userId, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId
            group by p.payer.id
            """)
    List<UserAmount> sumPaidByGroup(@Param("groupId") UUID groupId);

    /** Total received per payee across a group's settle-up payments (one aggregate query). */
    @Query("""
            select p.payee.id as userId, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId
            group by p.payee.id
            """)
    List<UserAmount> sumReceivedByGroup(@Param("groupId") UUID groupId);

    /** Payment history for a group, newest first, with payer and payee fetched (no N+1). */
    @Query("""
            select p from Payment p
            join fetch p.payer
            join fetch p.payee
            where p.group.id = :groupId
            order by p.createdAt desc
            """)
    List<Payment> findByGroupIdFetchUsers(@Param("groupId") UUID groupId);
}
