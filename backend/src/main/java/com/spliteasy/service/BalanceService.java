package com.spliteasy.service;

import com.spliteasy.dto.GroupBalancesResponse;
import com.spliteasy.dto.MemberBalance;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserAmount;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes net balances per group member from already-persisted expense data.
 * It does NOT re-run the split — it reads the stored {@code paid_by}/{@code amount_cents}
 * and per-participant {@code share_cents}. For each member:
 *
 * <pre>net = (expense amount paid) - (expense shares owed) + (payments made) - (payments received)</pre>
 *
 * Positive means the member is owed money; negative means they owe. A settle-up payment
 * {@code payer -> payee} of X raises the payer's net by X (they now owe less) and lowers the
 * payee's by X (they're now owed less) — folded in through the same aggregate path as expenses,
 * not a special case. Because expense shares sum to their amount and each payment contributes
 * +X and -X, the group's balances always sum to zero. All aggregate GROUP BY queries — no loops.
 */
@Service
public class BalanceService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseParticipantRepository participantRepository;
    private final GroupMembershipRepository membershipRepository;
    private final PaymentRepository paymentRepository;
    private final MembershipGuard membershipGuard;

    public BalanceService(
            ExpenseRepository expenseRepository,
            ExpenseParticipantRepository participantRepository,
            GroupMembershipRepository membershipRepository,
            PaymentRepository paymentRepository,
            MembershipGuard membershipGuard) {
        this.expenseRepository = expenseRepository;
        this.participantRepository = participantRepository;
        this.membershipRepository = membershipRepository;
        this.paymentRepository = paymentRepository;
        this.membershipGuard = membershipGuard;
    }

    @Transactional(readOnly = true)
    public GroupBalancesResponse computeBalances(UUID requesterId, UUID groupId) {
        membershipGuard.requireMember(groupId, requesterId);

        Map<UUID, Long> paid = toMap(expenseRepository.sumPaidByGroup(groupId));
        Map<UUID, Long> owed = toMap(participantRepository.sumOwedByGroup(groupId));
        Map<UUID, Long> settledOut = toMap(paymentRepository.sumPaidByGroup(groupId));
        Map<UUID, Long> settledIn = toMap(paymentRepository.sumReceivedByGroup(groupId));

        // Include every current member, so freshly-added or uninvolved members show 0.
        List<MemberBalance> balances = membershipRepository.findByGroupIdFetchUser(groupId).stream()
                .map(m -> m.getUser())
                .map(user -> {
                    UUID id = user.getId();
                    long net = paid.getOrDefault(id, 0L) - owed.getOrDefault(id, 0L)
                            + settledOut.getOrDefault(id, 0L) - settledIn.getOrDefault(id, 0L);
                    return new MemberBalance(UserSummary.from(user), net);
                })
                .sorted(Comparator.comparing((MemberBalance b) -> b.user().displayName()))
                .toList();

        return new GroupBalancesResponse(groupId, balances);
    }

    private Map<UUID, Long> toMap(List<UserAmount> rows) {
        Map<UUID, Long> map = new HashMap<>();
        for (UserAmount row : rows) {
            map.put(row.getUserId(), row.getTotalCents());
        }
        return map;
    }
}
