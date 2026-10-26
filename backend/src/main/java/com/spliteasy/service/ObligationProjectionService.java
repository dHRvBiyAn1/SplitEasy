package com.spliteasy.service;

import com.spliteasy.dto.balance.DirectObligation;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.PairAmount;
import com.spliteasy.repository.PaymentRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Canonical obligations for the selected mode; recorded history is never changed. */
@Service
@RequiredArgsConstructor
public class ObligationProjectionService {
  private final BalanceService balanceService;
  private final ExpenseParticipantRepository participantRepository;
  private final PaymentRepository paymentRepository;
  private final FinancialParticipantService financialParticipants;
  private final GroupRepository groupRepository;
  private final MembershipGuard membershipGuard;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DirectObligation> forGroup(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return snapshot(requesterId, groupId);
  }

  // Callers own authorization and a read snapshot, or hold the group's write guard.
  List<DirectObligation> snapshot(UUID requesterId, UUID groupId) {
    var group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Group not found"));
    return snapshot(requesterId, groupId, group.isSimplifyDebts());
  }

  List<DirectObligation> snapshot(UUID requesterId, UUID groupId, boolean simplify) {
    if (!simplify) return rawSnapshot(groupId);
    return NetSettlementCalculator.calculate(
            balanceService.computeBalances(requesterId, groupId).balances())
        .stream()
        .map(t -> new DirectObligation(t.from(), t.to(), t.amountMinor(), t.currency()))
        .toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DirectObligation> rawForGroup(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return rawSnapshot(groupId);
  }

  private List<DirectObligation> rawSnapshot(UUID groupId) {
    Map<Pair, Long> amounts = new HashMap<>();
    addPairs(groupId, amounts, participantRepository.sumOwedByPair(groupId), 1);
    addPairs(groupId, amounts, paymentRepository.sumPaidByPair(groupId), -1);
    Set<UUID> ledgerIds = new HashSet<>();
    amounts
        .keySet()
        .forEach(
            pair -> {
              ledgerIds.add(pair.from());
              ledgerIds.add(pair.to());
            });
    var people = financialParticipants.resolve(groupId, ledgerIds);
    return amounts.entrySet().stream()
        .filter(entry -> entry.getValue() > 0)
        .map(
            entry ->
                new DirectObligation(
                    people.get(entry.getKey().from()),
                    people.get(entry.getKey().to()),
                    entry.getValue(),
                    entry.getKey().currency()))
        .sorted(
            Comparator.comparing((DirectObligation debt) -> debt.from().id())
                .thenComparing(debt -> debt.to().id()))
        .toList();
  }

  private void addPairs(UUID groupId, Map<Pair, Long> amounts, List<PairAmount> rows, int sign) {
    var originals = financialParticipants.originals(groupId);
    for (PairAmount row : rows) {
      UUID from = originals.get(row.getFromId()).canonicalParticipantId();
      UUID to = originals.get(row.getToId()).canonicalParticipantId();
      if (from.equals(to)) continue;
      long amount = row.getTotalCents() * sign;
      amounts.merge(new Pair(from, to, row.getCurrency()), amount, Math::addExact);
      amounts.merge(new Pair(to, from, row.getCurrency()), -amount, Math::addExact);
    }
  }

  private record Pair(UUID from, UUID to, com.spliteasy.entity.LedgerCurrency currency) {}
}
