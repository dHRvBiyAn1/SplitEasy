package com.spliteasy.service;

import com.spliteasy.dto.balance.MemberBalance;
import com.spliteasy.dto.balance.SimplifiedDebtsResponse;
import com.spliteasy.dto.balance.SuggestedTransaction;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives suggested greedy settle-up transfers from a group's current balances. Reads the netted
 * balances from {@link BalanceService} (which already folds expenses + payments and guarantees a
 * zero-sum group) — it does NOT re-aggregate anything itself. Read-only and on demand: nothing is
 * persisted.
 *
 * <p>Algorithm: greedy max-creditor / max-debtor matching. Repeatedly settle the largest amount
 * possible between the biggest debtor and biggest creditor. This is a well-known near-optimal
 * heuristic for the minimum-transaction problem; it yields at most N-1 transfers but does not
 * guarantee the globally fewest transfers. For zero-sum integer-cent balances, the transfer plan
 * settles every member to exactly zero with no leftover cents.
 */
@Service
@RequiredArgsConstructor
public class DebtSimplificationService {

  private final BalanceService balanceService;
  private final GroupRepository groupRepository;

  // BalanceService joins this transaction, so this boundary must own the consistent snapshot.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SimplifiedDebtsResponse simplify(UUID requesterId, UUID groupId) {
    // Reuse the single source of balance truth (also enforces group membership -> 403).
    List<MemberBalance> balances = balanceService.computeBalances(requesterId, groupId).balances();
    boolean direct =
        !groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Group not found"))
            .isSimplifyDebts();
    return new SimplifiedDebtsResponse(
        groupId,
        simplify(balances),
        direct
            ? "This net-derived preview may leave offsetting direct obligations. Use current obligations to settle direct debts."
            : null);
  }

  static List<SuggestedTransaction> simplify(List<MemberBalance> balances) {
    return NetSettlementCalculator.calculate(balances);
  }
}
