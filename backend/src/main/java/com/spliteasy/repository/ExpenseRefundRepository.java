package com.spliteasy.repository;

import com.spliteasy.entity.ExpenseRefund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Refunds reverse expense economics, so every aggregate here is subtracted by its caller. All
 * exclude soft-deleted refunds; a deleted refund keeps its row and its revisions.
 */
public interface ExpenseRefundRepository extends JpaRepository<ExpenseRefund, UUID> {

  /** Total already refunded against one expense — the remainder guard for a new refund. */
  @Query(
      """
      select coalesce(sum(r.amountCents), 0) from ExpenseRefund r
      where r.expense.id = :expenseId and r.deletedAt is null
      """)
  long sumActiveByExpense(@Param("expenseId") UUID expenseId);

  /** Refund money received, per receiving participant — subtracted from what they paid. */
  @Query(
      """
      select r.receivedBy.id as userId, r.currency as currency, sum(r.amountCents) as totalCents
      from ExpenseRefund r
      where r.group.id = :groupId and r.deletedAt is null
      group by r.receivedBy.id, r.currency
      """)
  List<UserAmount> sumReceivedByGroup(@Param("groupId") UUID groupId);

  /** Refund allocation, per participant — subtracted from what they owe. */
  @Query(
      """
      select p.user.id as userId, p.refund.currency as currency, sum(p.shareCents) as totalCents
      from ExpenseRefundParticipant p
      where p.refund.group.id = :groupId and p.refund.deletedAt is null
      group by p.user.id, p.refund.currency
      """)
  List<UserAmount> sumAllocatedByGroup(@Param("groupId") UUID groupId);

  /** Refund allocation as debtor → creditor pairs, subtracted from the raw expense obligations. */
  @Query(
      """
      select p.user.id as fromId, p.refund.currency as currency,
             p.refund.receivedBy.id as toId, sum(p.shareCents) as totalCents
      from ExpenseRefundParticipant p
      where p.refund.group.id = :groupId and p.refund.deletedAt is null
        and p.user.id <> p.refund.receivedBy.id
      group by p.user.id, p.refund.receivedBy.id, p.refund.currency
      """)
  List<PairAmount> sumByPair(@Param("groupId") UUID groupId);

  /** Refunded money per currency — subtracted from a group's gross spend on the dashboard. */
  @Query(
      """
      select r.currency as currency, sum(r.amountCents) as totalCents
      from ExpenseRefund r
      where r.group.id = :groupId and r.deletedAt is null
      group by r.currency
      """)
  List<ExpenseRepository.CurrencyAmount> sumAmountsByGroup(@Param("groupId") UUID groupId);

  @Query(
      """
      select r from ExpenseRefund r
      join fetch r.receivedBy
      left join fetch r.recorder
      where r.expense.id = :expenseId and r.deletedAt is null
      order by r.refundedOn desc, r.createdAt desc
      """)
  List<ExpenseRefund> findActiveByExpenseId(@Param("expenseId") UUID expenseId);

  @Query(
      """
      select r from ExpenseRefund r
      join fetch r.receivedBy
      join fetch r.expense
      join fetch r.group
      left join fetch r.recorder
      where r.id = :id
      """)
  Optional<ExpenseRefund> findByIdFetchAll(@Param("id") UUID id);
}
