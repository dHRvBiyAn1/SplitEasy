package com.spliteasy.service;

import com.spliteasy.dto.GroupBalancesResponse;
import com.spliteasy.dto.MemberBalance;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
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
 * <pre>net = (total they paid) - (total of their split shares)</pre>
 *
 * Positive means the member is owed money; negative means they owe. Because every
 * expense's shares sum to its amount, the group's balances always sum to zero.
 * Uses two aggregate GROUP BY queries — no per-expense loop.
 */
@Service
public class BalanceService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseParticipantRepository participantRepository;
    private final GroupMembershipRepository membershipRepository;

    public BalanceService(
            ExpenseRepository expenseRepository,
            ExpenseParticipantRepository participantRepository,
            GroupMembershipRepository membershipRepository) {
        this.expenseRepository = expenseRepository;
        this.participantRepository = participantRepository;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(readOnly = true)
    public GroupBalancesResponse computeBalances(UUID requesterId, UUID groupId) {
        requireMember(groupId, requesterId);

        Map<UUID, Long> paid = toMap(expenseRepository.sumPaidByGroup(groupId));
        Map<UUID, Long> owed = toMap(participantRepository.sumOwedByGroup(groupId));

        // Include every current member, so freshly-added or uninvolved members show 0.
        List<MemberBalance> balances = membershipRepository.findByGroupIdFetchUser(groupId).stream()
                .map(m -> m.getUser())
                .map(user -> new MemberBalance(
                        UserSummary.from(user),
                        paid.getOrDefault(user.getId(), 0L) - owed.getOrDefault(user.getId(), 0L)))
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

    private void requireMember(UUID groupId, UUID userId) {
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("You are not a member of this group");
        }
    }
}
