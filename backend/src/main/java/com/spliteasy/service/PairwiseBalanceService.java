package com.spliteasy.service;

import com.spliteasy.dto.PersonBalance;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserAmount;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairwise balances between one member and each other member of a group — the "who owes whom,
 * to me specifically" ledger the dashboard and the group balance-pills render. Unlike
 * {@link BalanceService} (aggregate net per member) and {@link DebtSimplificationService}
 * (minimal netted transfers), this keeps each counterparty relationship separate, so it can
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
public class PairwiseBalanceService {

    private final ExpenseParticipantRepository participantRepository;
    private final PaymentRepository paymentRepository;
    private final GroupMembershipRepository membershipRepository;
    private final MembershipGuard membershipGuard;

    public PairwiseBalanceService(
            ExpenseParticipantRepository participantRepository,
            PaymentRepository paymentRepository,
            GroupMembershipRepository membershipRepository,
            MembershipGuard membershipGuard) {
        this.participantRepository = participantRepository;
        this.paymentRepository = paymentRepository;
        this.membershipRepository = membershipRepository;
        this.membershipGuard = membershipGuard;
    }

    /** Guarded entry point for a single group (enforces membership → 403). */
    @Transactional(readOnly = true)
    public List<PersonBalance> forGroup(UUID requesterId, UUID groupId) {
        membershipGuard.requireMember(groupId, requesterId);
        return compute(requesterId, groupId);
    }

    /**
     * Unguarded compute — callers that already resolved the user's own groups (e.g. the dashboard)
     * skip the redundant membership check. Returns every counterparty with a non-zero balance,
     * ordered by magnitude (largest first) then name.
     */
    @Transactional(readOnly = true)
    public List<PersonBalance> compute(UUID requesterId, UUID groupId) {
        Map<UUID, Long> owedToMe = toMap(participantRepository.sumOwedToUser(groupId, requesterId));
        Map<UUID, Long> iOwe = toMap(participantRepository.sumOwedByUser(groupId, requesterId));
        Map<UUID, Long> iPaid = toMap(paymentRepository.sumPaidByUserToEach(groupId, requesterId));
        Map<UUID, Long> paidMe = toMap(paymentRepository.sumReceivedByUserFromEach(groupId, requesterId));

        // Every current member (except me) so a counterparty appears even if only payments exist.
        return membershipRepository.findByGroupIdFetchUser(groupId).stream()
                .map(m -> m.getUser())
                .filter(u -> !u.getId().equals(requesterId))
                .map(u -> {
                    UUID id = u.getId();
                    long net = owedToMe.getOrDefault(id, 0L) - iOwe.getOrDefault(id, 0L)
                            + iPaid.getOrDefault(id, 0L) - paidMe.getOrDefault(id, 0L);
                    return new PersonBalance(UserSummary.from(u), net);
                })
                .filter(pb -> pb.netCents() != 0)
                .sorted(Comparator.comparingLong((PersonBalance pb) -> Math.abs(pb.netCents())).reversed()
                        .thenComparing(pb -> pb.user().displayName()))
                .toList();
    }

    private Map<UUID, Long> toMap(List<UserAmount> rows) {
        return rows.stream().collect(Collectors.toMap(
                UserAmount::getUserId, UserAmount::getTotalCents, Long::sum, LinkedHashMap::new));
    }
}
