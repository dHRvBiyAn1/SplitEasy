package com.spliteasy.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who owes whom inside one multi-payer expense. Each person's own share is first covered by their
 * own contribution; debtors then owe creditors in proportion, floored, and the few leftover minor
 * units go debtors-by-UUID to creditors primary-payer-first (north-west corner), so every row and
 * column is exact. Design: docs/superpowers/plans/2026-11-11-alignment-e03.md.
 */
final class ContributionAttribution {
  private ContributionAttribution() {}

  record Debt(UUID from, UUID to, long amount) {}

  /** Inputs are keyed by canonical participant; {@code primary} is the expense's primary payer. */
  static List<Debt> attribute(Map<UUID, Long> paid, Map<UUID, Long> owed, UUID primary) {
    Map<UUID, Long> net = new HashMap<>(paid);
    owed.forEach((id, share) -> net.merge(id, -share, Math::addExact));
    List<UUID> debtors = net.keySet().stream().filter(id -> net.get(id) < 0).sorted().toList();
    List<UUID> creditors =
        net.keySet().stream()
            .filter(id -> net.get(id) > 0)
            .sorted(Comparator.comparing((UUID id) -> !id.equals(primary)).thenComparing(id -> id))
            .toList();
    BigInteger total =
        creditors.stream()
            .map(id -> BigInteger.valueOf(net.get(id)))
            .reduce(BigInteger.ZERO, BigInteger::add);
    long[][] owes = new long[debtors.size()][creditors.size()];
    long[] rowLeft = new long[debtors.size()];
    long[] columnLeft = new long[creditors.size()];
    for (int c = 0; c < creditors.size(); c++) columnLeft[c] = net.get(creditors.get(c));
    for (int d = 0; d < debtors.size(); d++) {
      long debt = -net.get(debtors.get(d));
      rowLeft[d] = debt;
      for (int c = 0; c < creditors.size(); c++) {
        long floor =
            BigInteger.valueOf(debt)
                .multiply(BigInteger.valueOf(net.get(creditors.get(c))))
                .divide(total)
                .longValueExact();
        owes[d][c] = floor;
        rowLeft[d] -= floor;
        columnLeft[c] -= floor;
      }
    }
    // Residual rows and columns have equal totals, so one corner-to-corner pass places them all.
    for (int d = 0, c = 0; d < debtors.size() && c < creditors.size(); ) {
      long units = Math.min(rowLeft[d], columnLeft[c]);
      owes[d][c] += units;
      rowLeft[d] -= units;
      columnLeft[c] -= units;
      if (rowLeft[d] == 0) d++;
      else c++;
    }
    List<Debt> debts = new ArrayList<>();
    for (int d = 0; d < debtors.size(); d++)
      for (int c = 0; c < creditors.size(); c++)
        if (owes[d][c] > 0) debts.add(new Debt(debtors.get(d), creditors.get(c), owes[d][c]));
    return debts;
  }
}
