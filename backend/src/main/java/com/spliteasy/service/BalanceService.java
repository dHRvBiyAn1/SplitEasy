package com.spliteasy.service;

import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.balance.MemberBalance;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserAmount;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes net balances per group member from already-persisted expense data. It does NOT re-run
 * the split — it reads the stored {@code paid_by}/{@code amount_cents} and per-participant {@code
 * share_cents}. For each member:
 *
 * <pre>net = (expense amount paid) - (expense shares owed) + (payments made) - (payments received)
 * </pre>
 *
 * Positive means the member is owed money; negative means they owe. A settle-up payment {@code
 * payer -> payee} of X raises the payer's net by X (they now owe less) and lowers the payee's by X
 * (they're now owed less) — folded in through the same aggregate path as expenses, not a special
 * case. Because expense shares sum to their amount and each payment contributes +X and -X, the
 * group's balances always sum to zero. All aggregate GROUP BY queries — no loops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

  private final ExpenseRepository expenseRepository;
  private final ExpenseParticipantRepository participantRepository;
  private final FinancialParticipantService financialParticipants;
  private final PaymentRepository paymentRepository;
  private final MembershipGuard membershipGuard;

  // Public reads share one snapshot; guarded writes join their stable, group-locked ledger.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public GroupBalancesResponse computeBalances(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    log.debug("Computing balances for group {} (requested by user {})", groupId, requesterId);

    return snapshot(groupId);
  }

  // Invitation preview owns a verified capability; other callers first authorize membership.
  GroupBalancesResponse snapshot(UUID groupId) {
    Map<CurrencyUser, Long> paid = toMap(groupId, expenseRepository.sumPaidByGroup(groupId));
    Map<CurrencyUser, Long> owed = toMap(groupId, participantRepository.sumOwedByGroup(groupId));
    Map<CurrencyUser, Long> settledOut = toMap(groupId, paymentRepository.sumPaidByGroup(groupId));
    Map<CurrencyUser, Long> settledIn =
        toMap(groupId, paymentRepository.sumReceivedByGroup(groupId));
    Set<CurrencyUser> keys = new HashSet<>(paid.keySet());
    keys.addAll(owed.keySet());
    keys.addAll(settledOut.keySet());
    keys.addAll(settledIn.keySet());
    Set<UUID> ledgerIds = keys.stream().map(CurrencyUser::userId).collect(Collectors.toSet());
    var people = financialParticipants.resolve(groupId, ledgerIds);
    var currencies = keys.stream().map(CurrencyUser::currency).collect(Collectors.toSet());
    if (currencies.isEmpty()) currencies.add(com.spliteasy.entity.LedgerCurrency.USD);
    List<MemberBalance> balances =
        currencies.stream()
            .sorted()
            .flatMap(
                currency ->
                    people.values().stream()
                        .map(
                            user -> {
                              var key = new CurrencyUser(currency, user.id());
                              long net =
                                  Math.subtractExact(
                                      Math.addExact(
                                          paid.getOrDefault(key, 0L),
                                          settledOut.getOrDefault(key, 0L)),
                                      Math.addExact(
                                          owed.getOrDefault(key, 0L),
                                          settledIn.getOrDefault(key, 0L)));
                              return new MemberBalance(user, net, currency);
                            }))
            .sorted(
                Comparator.comparing((MemberBalance b) -> b.currency())
                    .thenComparing(b -> b.user().displayName()))
            .toList();

    return new GroupBalancesResponse(groupId, balances);
  }

  private record CurrencyUser(com.spliteasy.entity.LedgerCurrency currency, UUID userId) {}

  private Map<CurrencyUser, Long> toMap(UUID groupId, List<UserAmount> rows) {
    var originals = financialParticipants.originals(groupId);
    return rows.stream()
        .collect(
            Collectors.toMap(
                row ->
                    new CurrencyUser(
                        row.getCurrency(), originals.get(row.getUserId()).canonicalParticipantId()),
                UserAmount::getTotalCents,
                Math::addExact));
  }
}
