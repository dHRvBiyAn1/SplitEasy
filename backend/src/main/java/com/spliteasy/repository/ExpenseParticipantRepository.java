package com.spliteasy.repository;

import com.spliteasy.entity.ExpenseParticipant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExpenseParticipantRepository extends JpaRepository<ExpenseParticipant, UUID> {

  /**
   * Raw debts of single-payer expenses, grouped by debtor and creditor. Multi-payer expenses are
   * attributed separately ({@code ContributionAttribution}).
   */
  @Query(
      """
      select p.user.id as fromId, p.expense.currency as currency, p.expense.paidBy.id as toId, sum(p.shareCents) as totalCents
      from ExpenseParticipant p
      where p.expense.deletedAt is null and p.expense.group.id = :groupId and p.user.id <> p.expense.paidBy.id
        and p.expense.id in (select c.expense.id from ExpenseContribution c
                             group by c.expense.id having count(*) = 1)
      group by p.user.id, p.expense.paidBy.id, p.expense.currency
      """)
  List<PairAmount> sumOwedByPair(@Param("groupId") UUID groupId);

  /**
   * Total share owed per user across all expenses in a group — one aggregate query, not a
   * per-expense loop.
   */
  @Query(
      """
            select p.user.id as userId, p.expense.currency as currency, sum(p.shareCents) as totalCents
            from ExpenseParticipant p
            where p.expense.deletedAt is null and p.expense.group.id = :groupId
            group by p.user.id, p.expense.currency
            """)
  List<UserAmount> sumOwedByGroup(@Param("groupId") UUID groupId);

  /**
   * One user's share of each expense across a set of groups — expenseId → shareCents. Used to
   * derive the viewer's per-expense "you lent / you borrowed" delta without an N+1.
   */
  @Query(
      """
            select p.expense.id as expenseId, sum(p.shareCents) as shareCents
            from ExpenseParticipant p
            where p.expense.deletedAt is null and p.user.accountId = :userId and p.expense.group.id in :groupIds
            group by p.expense.id
            """)
  List<ExpenseShareView> findSharesByUserAndGroups(
      @Param("userId") UUID userId, @Param("groupIds") List<UUID> groupIds);

  /** expenseId → the requesting user's own share, in cents. */
  interface ExpenseShareView {
    UUID getExpenseId();

    long getShareCents();
  }
}
