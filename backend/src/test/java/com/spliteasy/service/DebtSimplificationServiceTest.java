package com.spliteasy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.spliteasy.dto.MemberBalance;
import com.spliteasy.dto.SuggestedTransaction;
import com.spliteasy.dto.UserSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the greedy simplification (no Spring/DB). */
class DebtSimplificationServiceTest {

    private static UserSummary user(String name) {
        return new UserSummary(UUID.randomUUID(), name.toLowerCase() + "@example.com", name);
    }

    private static MemberBalance bal(UserSummary u, long netCents) {
        return new MemberBalance(u, netCents);
    }

    /** Apply the suggested transfers to the starting nets; every member must land on exactly 0. */
    private static Map<UUID, Long> applyAll(List<MemberBalance> start, List<SuggestedTransaction> txns) {
        Map<UUID, Long> net = new HashMap<>();
        for (MemberBalance b : start) {
            net.put(b.user().id(), b.netCents());
        }
        for (SuggestedTransaction t : txns) {
            // debtor (from) pays creditor (to): from.net += amount, to.net -= amount.
            net.merge(t.from().id(), t.amountCents(), Long::sum);
            net.merge(t.to().id(), -t.amountCents(), Long::sum);
        }
        return net;
    }

    @Test
    void chainCollapsesAndExcludesTheZeroBalanceMiddleman() {
        // A owes B 10, B owes C 10 → nets: A -1000, B 0, C +1000.
        UserSummary a = user("Alice");
        UserSummary b = user("Bob");
        UserSummary c = user("Carol");
        List<MemberBalance> balances = List.of(bal(a, -1000), bal(b, 0), bal(c, 1000));

        List<SuggestedTransaction> txns = DebtSimplificationService.simplify(balances);

        // Collapses two debts into one transfer A -> C; Bob (net 0) never appears.
        assertThat(txns).hasSize(1);
        assertThat(txns.get(0).from().id()).isEqualTo(a.id());
        assertThat(txns.get(0).to().id()).isEqualTo(c.id());
        assertThat(txns.get(0).amountCents()).isEqualTo(1000);
        assertThat(txns).noneMatch(t -> t.from().id().equals(b.id()) || t.to().id().equals(b.id()));
        assertThat(txns).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
        assertThat(applyAll(balances, txns).values()).allMatch(v -> v == 0L);
    }

    @Test
    void fullySettledGroupProducesNoTransactions() {
        List<MemberBalance> balances = List.of(bal(user("Alice"), 0), bal(user("Bob"), 0));
        assertThat(DebtSimplificationService.simplify(balances)).isEmpty();
    }

    @Test
    void oddParticipantCountWithUnevenAmountsNetsToZeroWithNoLeftover() {
        // Three debtors of uneven amounts, one creditor. Debts sum to the credit exactly.
        UserSummary a = user("Alice");
        UserSummary b = user("Bob");
        UserSummary c = user("Carol");
        UserSummary d = user("Dave");
        UserSummary e = user("Eve");
        List<MemberBalance> balances =
                List.of(bal(a, -333), bal(b, -333), bal(c, -334), bal(d, 1000), bal(e, 0));

        List<SuggestedTransaction> txns = DebtSimplificationService.simplify(balances);

        assertThat(txns).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
        // Sum of transfers equals the total debt; no cent invented or lost.
        long total = txns.stream().mapToLong(SuggestedTransaction::amountCents).sum();
        assertThat(total).isEqualTo(1000);
        // Everyone settles to exactly zero.
        assertThat(applyAll(balances, txns).values()).allMatch(v -> v == 0L);
        // Eve (net 0) is excluded.
        assertThat(txns).noneMatch(t -> t.from().id().equals(e.id()) || t.to().id().equals(e.id()));
        // At most N-1 transfers (N = participants with a non-zero balance = 4).
        assertThat(txns.size()).isLessThanOrEqualTo(3);
    }

    @Test
    void multipleDebtorsAndCreditorsAllSettleToZero() {
        UserSummary a = user("Alice");
        UserSummary b = user("Bob");
        UserSummary c = user("Carol");
        UserSummary d = user("Dave");
        List<MemberBalance> balances = List.of(bal(a, -700), bal(b, -300), bal(c, 500), bal(d, 500));

        List<SuggestedTransaction> txns = DebtSimplificationService.simplify(balances);

        assertThat(txns).isNotEmpty();
        assertThat(txns).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
        assertThat(txns.size()).isLessThanOrEqualTo(3); // <= N-1
        assertThat(applyAll(balances, txns).values()).allMatch(v -> v == 0L);
        // Transfers are sorted largest-first for a predictable UI.
        List<Long> amounts = txns.stream().map(SuggestedTransaction::amountCents).toList();
        List<Long> sortedDesc = new ArrayList<>(amounts);
        sortedDesc.sort((x, y) -> Long.compare(y, x));
        assertThat(amounts).isEqualTo(sortedDesc);
    }
}
