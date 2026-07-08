package com.spliteasy.service;

import com.spliteasy.dto.MemberBalance;
import com.spliteasy.dto.SimplifiedDebtsResponse;
import com.spliteasy.dto.SuggestedTransaction;
import com.spliteasy.dto.UserSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a minimal set of settle-up transfers from a group's current balances. Reads the netted
 * balances from {@link BalanceService} (which already folds expenses + payments and guarantees a
 * zero-sum group) — it does NOT re-aggregate anything itself. Read-only and on demand: nothing is
 * persisted.
 *
 * <p>Algorithm: greedy max-creditor / max-debtor matching. Repeatedly settle the largest amount
 * possible between the biggest debtor and biggest creditor. This is a well-known near-optimal
 * heuristic for the (NP-hard) minimum-transaction problem; it yields at most N-1 transfers and is
 * optimal in the common cases. Because balances are zero-sum integer cents, the transfers settle
 * every member to exactly zero with no leftover cents.
 */
@Service
public class DebtSimplificationService {

    private final BalanceService balanceService;

    public DebtSimplificationService(BalanceService balanceService) {
        this.balanceService = balanceService;
    }

    @Transactional(readOnly = true)
    public SimplifiedDebtsResponse simplify(UUID requesterId, UUID groupId) {
        // Reuse the single source of balance truth (also enforces group membership -> 403).
        List<MemberBalance> balances = balanceService.computeBalances(requesterId, groupId).balances();
        return new SimplifiedDebtsResponse(groupId, simplify(balances));
    }

    /** Mutable creditor/debtor holder for the greedy loop. */
    private record Party(UserSummary user, long remaining) {}

    /**
     * Pure greedy simplification over already-netted balances. Members with a zero net are excluded
     * (never produce a $0 transaction). Returns transfers ordered by amount, largest first.
     */
    static List<SuggestedTransaction> simplify(List<MemberBalance> balances) {
        // Max-heaps keyed by outstanding amount.
        PriorityQueue<Party> creditors = new PriorityQueue<>(Comparator.comparingLong(Party::remaining).reversed());
        PriorityQueue<Party> debtors = new PriorityQueue<>(Comparator.comparingLong(Party::remaining).reversed());
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
            transactions.add(new SuggestedTransaction(debtor.user(), creditor.user(), amount));

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
        transactions.sort(Comparator.comparingLong(SuggestedTransaction::amountCents).reversed());
        return transactions;
    }
}
