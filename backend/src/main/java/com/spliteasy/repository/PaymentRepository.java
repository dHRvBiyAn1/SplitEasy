package com.spliteasy.repository;

import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.entity.Payment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every financial aggregate here excludes soft-deleted payments (B01). A deleted settlement keeps
 * its row and its revisions, but it must not appear in any balance, obligation or feed.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByGroupIdAndRecordedByAndOperationId(
      UUID groupId, UUID recordedBy, UUID operationId);

  // PostgreSQL waits for a concurrent winner without aborting the losing transaction.
  @Modifying
  @Query(
      value =
          """
      insert into payments
        (id, group_id, payer_id, payee_id, amount_cents, currency, created_at, recorded_by,
         operation_id, paid_on, method, note)
      values (:id, :groupId, :payerId, :payeeId, :amountCents, :currency, :createdAt, :requesterId,
              :operationId, :paidOn, :method, :note)
      on conflict (group_id, recorded_by, operation_id) do nothing
      """,
      nativeQuery = true)
  int insertOperation(
      UUID id,
      UUID groupId,
      UUID payerId,
      UUID payeeId,
      long amountCents,
      String currency,
      Instant createdAt,
      UUID requesterId,
      UUID operationId,
      LocalDate paidOn,
      String method,
      String note);

  @Query(
      "select distinct p.currency from Payment p where p.group.id = :groupId and p.deletedAt is null")
  List<LedgerCurrency> findCurrenciesByGroupId(@Param("groupId") UUID groupId);

  /** Recorded payments, grouped by payer and payee across the whole group. */
  @Query(
      """
      select p.payer.id as fromId, p.currency as currency, p.payee.id as toId, sum(p.amountCents) as totalCents
      from Payment p
      where p.group.id = :groupId and p.deletedAt is null and p.payer.id <> p.payee.id
      group by p.payer.id, p.payee.id, p.currency
      """)
  List<PairAmount> sumPaidByPair(@Param("groupId") UUID groupId);

  /** Total paid out per payer across a group's settle-up payments (one aggregate query). */
  @Query(
      """
            select p.payer.id as userId, p.currency as currency, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId and p.deletedAt is null
            group by p.payer.id, p.currency
            """)
  List<UserAmount> sumPaidByGroup(@Param("groupId") UUID groupId);

  /** Total received per payee across a group's settle-up payments (one aggregate query). */
  @Query(
      """
            select p.payee.id as userId, p.currency as currency, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId and p.deletedAt is null
            group by p.payee.id, p.currency
            """)
  List<UserAmount> sumReceivedByGroup(@Param("groupId") UUID groupId);

  /** Total the given user paid to each payee in a group (payer = user), grouped by payee. */
  @Query(
      """
            select p.payee.id as userId, p.currency as currency, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId and p.deletedAt is null and p.payer.id = :userId
            group by p.payee.id, p.currency
            """)
  List<UserAmount> sumPaidByUserToEach(
      @Param("groupId") UUID groupId, @Param("userId") UUID userId);

  /** Total each payer paid to the given user in a group (payee = user), grouped by payer. */
  @Query(
      """
            select p.payer.id as userId, p.currency as currency, sum(p.amountCents) as totalCents
            from Payment p
            where p.group.id = :groupId and p.deletedAt is null and p.payee.id = :userId
            group by p.payer.id, p.currency
            """)
  List<UserAmount> sumReceivedByUserFromEach(
      @Param("groupId") UUID groupId, @Param("userId") UUID userId);

  /**
   * Payment history for a group, newest financial date first, with payer, payee and recorder
   * fetched (no N+1).
   */
  @Query(
      """
            select p from Payment p
            join fetch p.payer
            join fetch p.payee
            left join fetch p.recorder
            where p.group.id = :groupId and p.deletedAt is null
            order by p.paidOn desc, p.createdAt desc
            """)
  List<Payment> findByGroupIdFetchUsers(@Param("groupId") UUID groupId);

  /** The group's recoverable payments, most recently deleted first. */
  @Query(
      """
            select p from Payment p
            join fetch p.payer
            join fetch p.payee
            left join fetch p.recorder
            where p.group.id = :groupId and p.deletedAt is not null
            order by p.deletedAt desc, p.id
            """)
  List<Payment> findDeletedByGroupId(@Param("groupId") UUID groupId);

  /** One payment with everything a response needs, active or deleted. */
  @Query(
      """
            select p from Payment p
            join fetch p.payer
            join fetch p.payee
            join fetch p.group
            left join fetch p.recorder
            where p.id = :id
            """)
  Optional<Payment> findByIdFetchAll(@Param("id") UUID id);

  /**
   * Recent payments across a set of groups (the user's groups) for the activity feed, newest first,
   * with payer + payee + group fetched. Caller passes a {@link Pageable} to cap rows.
   */
  @Query(
      """
            select p from Payment p
            join fetch p.payer
            join fetch p.payee
            join fetch p.group
            where p.group.id in :groupIds and p.deletedAt is null
            order by p.createdAt desc
            """)
  List<Payment> findRecentByGroupIds(@Param("groupIds") List<UUID> groupIds, Pageable pageable);
}
