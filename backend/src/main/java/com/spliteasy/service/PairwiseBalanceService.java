package com.spliteasy.service;

import com.spliteasy.dto.balance.DirectObligation;
import com.spliteasy.dto.balance.PersonBalance;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.PairAmount;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserAmount;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairwise balances between one member and each other member of a group — the "who owes whom, to me
 * specifically" ledger the dashboard and the group balance-pills render. Unlike {@link
 * BalanceService} (aggregate net per member) and {@link DebtSimplificationService} (greedy
 * suggested netted transfers), this keeps each counterparty relationship separate, so it can
 * simultaneously show "Dan owes you $50" and "you owe Sofia $50" without netting them together.
 *
 * <p>For counterparty X, from the requesting user's perspective (positive = X owes you):
 *
 * <pre>net(X) = (X's share of my expenses) - (my share of X's expenses)
 *          + (I paid X) - (X paid me)</pre>
 *
 * These sum to the user's group net from {@link BalanceService}, so the two views stay consistent.
 * All aggregate GROUP BY queries — no per-expense loops.
 */
@Service
@RequiredArgsConstructor
public class PairwiseBalanceService {

  private final ExpenseParticipantRepository participantRepository;
  private final PaymentRepository paymentRepository;
  private final FinancialParticipantService financialParticipants;
  private final MembershipGuard membershipGuard;

  /** Guarded entry point for a single group (enforces membership → 403). */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PersonBalance> forGroup(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    return compute(requesterId, groupId);
  }

  /** Every raw bilateral obligation, independent of the group's simplify-debts setting. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DirectObligation> obligations(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    Map<Pair, Long> amounts = new HashMap<>();
    addPairs(amounts, participantRepository.sumOwedByPair(groupId), 1);
    addPairs(amounts, paymentRepository.sumPaidByPair(groupId), -1);
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
                    entry.getValue()))
        .sorted(
            Comparator.comparing((DirectObligation debt) -> debt.from().id())
                .thenComparing(debt -> debt.to().id()))
        .toList();
  }

  private void addPairs(Map<Pair, Long> amounts, List<PairAmount> rows, int sign) {
    for (PairAmount row : rows) {
      long amount = row.getTotalCents() * sign;
      amounts.merge(new Pair(row.getFromId(), row.getToId()), amount, Long::sum);
      amounts.merge(new Pair(row.getToId(), row.getFromId()), -amount, Long::sum);
    }
  }

  private record Pair(UUID from, UUID to) {}

  /**
   * Unguarded compute — callers that already resolved the user's own groups (e.g. the dashboard)
   * skip the redundant membership check. Returns every counterparty with a non-zero balance,
   * ordered by magnitude (largest first) then name. An existing caller transaction must also use
   * REPEATABLE_READ for a read snapshot (see DashboardService); guarded writes hold the group lock.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PersonBalance> compute(UUID requesterId, UUID groupId) {
    Map<UUID, Long> owedToMe = toMap(participantRepository.sumOwedToUser(groupId, requesterId));
    Map<UUID, Long> iOwe = toMap(participantRepository.sumOwedByUser(groupId, requesterId));
    Map<UUID, Long> iPaid = toMap(paymentRepository.sumPaidByUserToEach(groupId, requesterId));
    Map<UUID, Long> paidMe =
        toMap(paymentRepository.sumReceivedByUserFromEach(groupId, requesterId));

    Set<UUID> ledgerIds = new HashSet<>(owedToMe.keySet());
    ledgerIds.addAll(iOwe.keySet());
    ledgerIds.addAll(iPaid.keySet());
    ledgerIds.addAll(paidMe.keySet());
    return financialParticipants.resolve(groupId, ledgerIds).values().stream()
        .filter(u -> !u.id().equals(requesterId))
        .map(
            u -> {
              UUID id = u.id();
              long net =
                  owedToMe.getOrDefault(id, 0L)
                      - iOwe.getOrDefault(id, 0L)
                      + iPaid.getOrDefault(id, 0L)
                      - paidMe.getOrDefault(id, 0L);
              return new PersonBalance(u, net);
            })
        .filter(pb -> pb.netCents() != 0)
        .sorted(
            Comparator.comparingLong((PersonBalance pb) -> Math.abs(pb.netCents()))
                .reversed()
                .thenComparing(pb -> pb.user().displayName()))
        .toList();
  }

  private Map<UUID, Long> toMap(List<UserAmount> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(
                UserAmount::getUserId, UserAmount::getTotalCents, Long::sum, LinkedHashMap::new));
  }
}
