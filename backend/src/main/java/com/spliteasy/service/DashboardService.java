package com.spliteasy.service;

import com.spliteasy.dto.balance.PersonBalance;
import com.spliteasy.dto.balance.Settlement;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.dashboard.ActivityItem;
import com.spliteasy.dto.dashboard.DashboardGroup;
import com.spliteasy.dto.dashboard.DashboardResponse;
import com.spliteasy.dto.group.GroupSummary;

import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.ExpenseRepository.ActivityExpenseView;

import lombok.RequiredArgsConstructor;

import com.spliteasy.repository.PaymentRepository;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the single dashboard payload from the user's perspective: cross-group totals, per-group
 * cards, the pairwise People list, the per-group Settlement rows (settle-up modal), and a merged
 * recent-activity feed. Reuses {@link GroupService} for the group list and {@link
 * PairwiseBalanceService} for balances — no balance math lives here.
 *
 * <p>ponytail: iterates the user's groups (a handful in practice), running pairwise queries per
 * group. Fine at this scale; if a user ever joins hundreds of groups, batch the aggregates by
 * (group, counterparty) in one query instead.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Cap on the merged activity feed. */
    private static final int ACTIVITY_LIMIT = 15;

    private final GroupService groupService;
    private final PairwiseBalanceService pairwiseService;
    private final ExpenseRepository expenseRepository;
    private final ExpenseParticipantRepository participantRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(UUID userId) {
        List<GroupSummary> myGroups = groupService.listMyGroups(userId);

        List<DashboardGroup> groupCards = new ArrayList<>();
        List<Settlement> settlements = new ArrayList<>();
        // counterparty id -> aggregated pairwise net across groups (+ their display info).
        Map<UUID, Long> peopleNet = new LinkedHashMap<>();
        Map<UUID, UserSummary> peopleInfo = new LinkedHashMap<>();

        for (GroupSummary g : myGroups) {
            List<PersonBalance> pairwise = pairwiseService.compute(userId, g.id());
            long owed = 0;
            long owe = 0;
            for (PersonBalance pb : pairwise) {
                settlements.add(new Settlement(g.id(), g.name(), pb.user(), pb.netCents()));
                if (pb.netCents() > 0) {
                    owed += pb.netCents();
                } else {
                    owe += -pb.netCents();
                }
                peopleNet.merge(pb.user().id(), pb.netCents(), Long::sum);
                peopleInfo.putIfAbsent(pb.user().id(), pb.user());
            }
            groupCards.add(new DashboardGroup(
                    g.id(), g.name(), g.type(), g.memberCount(),
                    expenseRepository.sumAmountByGroup(g.id()), owed, owe, owed - owe));
        }

        List<PersonBalance> people = peopleNet.entrySet().stream()
                .filter(e -> e.getValue() != 0)
                .map(e -> new PersonBalance(peopleInfo.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingLong((PersonBalance pb) -> Math.abs(pb.netCents())).reversed()
                        .thenComparing(pb -> pb.user().displayName()))
                .toList();

        // Headline totals are GROSS (summed across every pairwise balance in every group), not the
        // per-person net below — so owing someone in one group always shows in "You owe", even when
        // another group leaves you net-positive with them. totalNet still equals owed - owe.
        long owedCents = settlements.stream().filter(s -> s.netCents() > 0).mapToLong(Settlement::netCents).sum();
        long oweCents = settlements.stream().filter(s -> s.netCents() < 0).mapToLong(s -> -s.netCents()).sum();
        int owedPeople = (int) settlements.stream().filter(s -> s.netCents() > 0)
                .map(s -> s.counterparty().id()).distinct().count();
        int owePeople = (int) settlements.stream().filter(s -> s.netCents() < 0)
                .map(s -> s.counterparty().id()).distinct().count();

        // Largest imbalances first for the settle-up modal.
        settlements.sort(Comparator.comparingLong((Settlement s) -> Math.abs(s.netCents())).reversed());

        return new DashboardResponse(
                owedCents - oweCents, owedCents, owedPeople, oweCents, owePeople,
                myGroups.size(), groupCards, people, settlements,
                buildActivity(userId, myGroups.stream().map(GroupSummary::id).toList()));
    }

    private List<ActivityItem> buildActivity(UUID userId, List<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        PageRequest limit = PageRequest.of(0, ACTIVITY_LIMIT);
        Map<UUID, Long> myShare = participantRepository.findSharesByUserAndGroups(userId, groupIds).stream()
                .collect(Collectors.toMap(
                        ExpenseParticipantRepository.ExpenseShareView::getExpenseId,
                        ExpenseParticipantRepository.ExpenseShareView::getShareCents));

        List<ActivityItem> items = new ArrayList<>();
        for (ActivityExpenseView e : expenseRepository.findRecentByGroupIds(groupIds, limit)) {
            UserSummary payer = new UserSummary(e.getPayerId(), e.getPayerEmail(), e.getPayerDisplayName());
            items.add(new ActivityItem(
                    e.getId(), ActivityItem.Kind.EXPENSE, e.getGroupId(), e.getGroupName(),
                    e.getDescription(), payer, null, e.getAmountCents(),
                    ExpenseService.viewerDelta(userId, e.getPayerId(), e.getAmountCents(),
                            myShare.getOrDefault(e.getId(), 0L)),
                    e.getCategory(), e.getSpentOn()));
        }
        paymentRepository.findRecentByGroupIds(groupIds, limit).forEach(p -> items.add(new ActivityItem(
                p.getId(), ActivityItem.Kind.PAYMENT, p.getGroup().getId(), p.getGroup().getName(),
                "Settlement", UserSummary.from(p.getPayer()), UserSummary.from(p.getPayee()),
                p.getAmountCents(), 0L, null, p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate())));

        // Merge both streams and keep the most recent by spent/settled date.
        items.sort(Comparator.comparing(ActivityItem::date).reversed());
        return items.stream().limit(ACTIVITY_LIMIT).toList();
    }
}
