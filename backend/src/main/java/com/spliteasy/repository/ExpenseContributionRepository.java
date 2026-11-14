package com.spliteasy.repository;

import com.spliteasy.entity.ExpenseContribution;
import com.spliteasy.entity.LedgerCurrency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseContributionRepository extends JpaRepository<ExpenseContribution, UUID> {

  /** expenseId → what the viewer's owned participants paid; drives the list's viewer delta. */
  @Query(
      """
      select c.expense.id as expenseId, sum(c.amountCents) as shareCents
      from ExpenseContribution c
      where c.expense.deletedAt is null and c.participant.accountId = :userId
        and c.expense.group.id in :groupIds
      group by c.expense.id
      """)
  List<ExpenseParticipantRepository.ExpenseShareView> findPaidByUserAndGroups(
      @Param("userId") UUID userId, @Param("groupIds") List<UUID> groupIds);

  /** Contributions of the group's active multi-payer expenses, for pairwise attribution. */
  @Query(
      """
      select c.expense.id as expenseId, c.expense.currency as currency,
             c.expense.paidBy.id as primaryId, c.participant.id as participantId,
             c.amountCents as amountCents
      from ExpenseContribution c
      where c.expense.deletedAt is null and c.expense.group.id = :groupId
        and c.expense.id in (select x.expense.id from ExpenseContribution x
                             group by x.expense.id having count(*) > 1)
      """)
  List<AttributionRow> findMultiPayerContributions(@Param("groupId") UUID groupId);

  /** Shares of the same multi-payer expenses. */
  @Query(
      """
      select p.expense.id as expenseId, p.expense.currency as currency,
             p.expense.paidBy.id as primaryId, p.user.id as participantId,
             p.shareCents as amountCents
      from ExpenseParticipant p
      where p.expense.deletedAt is null and p.expense.group.id = :groupId
        and p.expense.id in (select x.expense.id from ExpenseContribution x
                             group by x.expense.id having count(*) > 1)
      """)
  List<AttributionRow> findMultiPayerShares(@Param("groupId") UUID groupId);

  /** What each contributor paid across the group's multi-payer expenses. */
  @Query(
      """
      select c.participant.id as userId, c.expense.currency as currency, sum(c.amountCents) as totalCents
      from ExpenseContribution c
      where c.expense.deletedAt is null and c.expense.group.id = :groupId
        and c.expense.id in (select x.expense.id from ExpenseContribution x
                             group by x.expense.id having count(*) > 1)
      group by c.participant.id, c.expense.currency
      """)
  List<UserAmount> sumPaidMultiByGroup(@Param("groupId") UUID groupId);

  interface AttributionRow {
    UUID getExpenseId();

    LedgerCurrency getCurrency();

    UUID getPrimaryId();

    UUID getParticipantId();

    long getAmountCents();
  }
}
