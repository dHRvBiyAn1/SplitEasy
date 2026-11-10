package com.spliteasy.service;

import com.spliteasy.dto.balance.MemberBalance;
import com.spliteasy.dto.balance.SuggestedTransaction;
import com.spliteasy.dto.common.ParticipantSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Pure deterministic greedy matching of net debtors and creditors. */
public final class NetSettlementCalculator {
  private NetSettlementCalculator() {}

  /** Mutable creditor/debtor holder for the greedy loop. */
  private record Party(ParticipantSummary user, long remaining) {}

  /**
   * Pure greedy simplification over already-netted balances. Members with a zero net are excluded
   * (never produce a $0 transaction). Returns transfers ordered by amount, largest first.
   */
  public static List<SuggestedTransaction> calculate(List<MemberBalance> balances) {
    return balances.stream()
        .collect(
            java.util.stream.Collectors.groupingBy(
                MemberBalance::currency,
                java.util.TreeMap::new,
                java.util.stream.Collectors.toList()))
        .values()
        .stream()
        .flatMap(partition -> calculateCurrency(partition).stream())
        .toList();
  }

  private static List<SuggestedTransaction> calculateCurrency(List<MemberBalance> balances) {
    var currency = balances.getFirst().currency();
    // Max-heaps keyed by outstanding amount.
    PriorityQueue<Party> creditors =
        new PriorityQueue<>(
            Comparator.comparingLong(Party::remaining)
                .reversed()
                .thenComparing(p -> p.user().id()));
    PriorityQueue<Party> debtors =
        new PriorityQueue<>(
            Comparator.comparingLong(Party::remaining)
                .reversed()
                .thenComparing(p -> p.user().id()));
    for (MemberBalance b : balances) {
      if (b.netCents() > 0) {
        creditors.add(new Party(b.user(), b.netCents()));
      } else if (b.netCents() < 0) {
        debtors.add(new Party(b.user(), -b.netCents())); // store owed magnitude as positive
      }
      // netCents == 0: settled, excluded.
    }

    List<SuggestedTransaction> transactions = new ArrayList<>();
    while (!creditors.isEmpty() && !debtors.isEmpty()) {
      Party creditor = creditors.poll();
      Party debtor = debtors.poll();
      long amount = Math.min(creditor.remaining(), debtor.remaining());
      // Debtor pays creditor (payer -> payee).
      transactions.add(new SuggestedTransaction(debtor.user(), creditor.user(), amount, currency));

      long creditorLeft = creditor.remaining() - amount;
      long debtorLeft = debtor.remaining() - amount;
      if (creditorLeft > 0) {
        creditors.add(new Party(creditor.user(), creditorLeft));
      }
      if (debtorLeft > 0) {
        debtors.add(new Party(debtor.user(), debtorLeft));
      }
    }
    // Stable, predictable order for the UI: largest transfer first.
    transactions.sort(
        Comparator.comparingLong(SuggestedTransaction::amountCents)
            .reversed()
            .thenComparing(t -> t.from().id())
            .thenComparing(t -> t.to().id()));
    return transactions;
  }
}
