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
import com.spliteasy.repository.PaymentRepository;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
@Slf4j
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

  // One snapshot for nested pairwise reads, group totals, expense shares and the activity feed.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public DashboardResponse getDashboard(UUID userId) {
    log.debug("Assembling dashboard for user {}", userId);
    List<GroupSummary> myGroups = groupService.listMyGroups(userId);

    List<DashboardGroup> groupCards = new ArrayList<>();
    List<Settlement> settlements = new ArrayList<>();
    record PersonCurrency(UUID userId, com.spliteasy.entity.LedgerCurrency currency) {}
    Map<PersonCurrency, Long> peopleNet = new LinkedHashMap<>();
    Map<UUID, UserSummary> peopleInfo = new LinkedHashMap<>();
    var allCurrencies = new java.util.TreeSet<com.spliteasy.entity.LedgerCurrency>();
    for (GroupSummary g : myGroups) {
      List<PersonBalance> pairwise = pairwiseService.compute(userId, g.id());
      Map<com.spliteasy.entity.LedgerCurrency, Long> spent =
          expenseRepository.sumAmountsByGroup(g.id()).stream()
              .collect(
                  Collectors.toMap(
                      ExpenseRepository.CurrencyAmount::getCurrency,
                      ExpenseRepository.CurrencyAmount::getTotalCents));
      var currencies = new java.util.TreeSet<>(spent.keySet());
      currencies.addAll(paymentRepository.findCurrenciesByGroupId(g.id()));
      for (PersonBalance pb : pairwise) {
        settlements.add(new Settlement(g.id(), g.name(), pb.user(), pb.netMinor(), pb.currency()));
        peopleNet.merge(
            new PersonCurrency(pb.user().id(), pb.currency()), pb.netMinor(), Math::addExact);
        peopleInfo.putIfAbsent(pb.user().id(), pb.user());
        currencies.add(pb.currency());
      }
      if (currencies.isEmpty()) currencies.add(com.spliteasy.entity.LedgerCurrency.USD);
      allCurrencies.addAll(currencies);
      var totals =
          currencies.stream()
              .map(
                  currency -> {
                    long owed =
                        pairwise.stream()
                            .filter(pb -> pb.currency() == currency && pb.netMinor() > 0)
                            .mapToLong(PersonBalance::netMinor)
                            .reduce(0, Math::addExact);
                    long owe =
                        pairwise.stream()
                            .filter(pb -> pb.currency() == currency && pb.netMinor() < 0)
                            .mapToLong(pb -> -pb.netMinor())
                            .reduce(0, Math::addExact);
                    return new com.spliteasy.dto.dashboard.GroupCurrencyTotals(
                        currency,
                        spent.getOrDefault(currency, 0L),
                        owed,
                        owe,
                        Math.subtractExact(owed, owe));
                  })
              .toList();
      var usd =
          totals.size() == 1
                  && totals.getFirst().currency() == com.spliteasy.entity.LedgerCurrency.USD
              ? totals.getFirst()
              : null;
      groupCards.add(
          new DashboardGroup(
              g.id(),
              g.name(),
              g.type(),
              g.memberCount(),
              usd == null ? null : usd.totalSpentMinor(),
              usd == null ? null : usd.youAreOwedMinor(),
              usd == null ? null : usd.youOweMinor(),
              usd == null ? null : usd.netMinor(),
              g.simplifyDebts(),
              totals));
    }
    List<PersonBalance> people =
        peopleNet.entrySet().stream()
            .filter(e -> e.getValue() != 0)
            .map(
                e ->
                    new PersonBalance(
                        peopleInfo.get(e.getKey().userId()), e.getValue(), e.getKey().currency()))
            .sorted(
                Comparator.comparing(PersonBalance::currency)
                    .thenComparing(pb -> pb.user().displayName()))
            .toList();
    if (allCurrencies.isEmpty()) allCurrencies.add(com.spliteasy.entity.LedgerCurrency.USD);
    var totals =
        allCurrencies.stream()
            .map(
                currency -> {
                  var rows = settlements.stream().filter(s -> s.currency() == currency).toList();
                  long owed =
                      rows.stream()
                          .filter(s -> s.netMinor() > 0)
                          .mapToLong(Settlement::netMinor)
                          .reduce(0, Math::addExact);
                  long owe =
                      rows.stream()
                          .filter(s -> s.netMinor() < 0)
                          .mapToLong(s -> -s.netMinor())
                          .reduce(0, Math::addExact);
                  return new com.spliteasy.dto.dashboard.CurrencyTotals(
                      currency,
                      Math.subtractExact(owed, owe),
                      owed,
                      (int)
                          rows.stream()
                              .filter(s -> s.netMinor() > 0)
                              .map(s -> s.counterparty().id())
                              .distinct()
                              .count(),
                      owe,
                      (int)
                          rows.stream()
                              .filter(s -> s.netMinor() < 0)
                              .map(s -> s.counterparty().id())
                              .distinct()
                              .count());
                })
            .toList();
    var usd =
        totals.size() == 1
                && totals.getFirst().currency() == com.spliteasy.entity.LedgerCurrency.USD
            ? totals.getFirst()
            : null;
    settlements.sort(
        Comparator.comparing(Settlement::currency)
            .thenComparing(
                Comparator.comparingLong((Settlement s) -> Math.abs(s.netMinor())).reversed()));
    int owedPeople =
        (int)
            settlements.stream()
                .filter(s -> s.netMinor() > 0)
                .map(s -> s.counterparty().id())
                .distinct()
                .count();
    int owePeople =
        (int)
            settlements.stream()
                .filter(s -> s.netMinor() < 0)
                .map(s -> s.counterparty().id())
                .distinct()
                .count();
    return new DashboardResponse(
        usd == null ? null : usd.totalNetMinor(),
        usd == null ? null : usd.owedMinor(),
        owedPeople,
        usd == null ? null : usd.oweMinor(),
        owePeople,
        myGroups.size(),
        groupCards,
        people,
        settlements,
        buildActivity(userId, myGroups.stream().map(GroupSummary::id).toList()),
        totals);
  }

  private List<ActivityItem> buildActivity(UUID userId, List<UUID> groupIds) {
    if (groupIds.isEmpty()) {
      return List.of();
    }
    PageRequest limit = PageRequest.of(0, ACTIVITY_LIMIT);
    Map<UUID, Long> myShare =
        participantRepository.findSharesByUserAndGroups(userId, groupIds).stream()
            .collect(
                Collectors.toMap(
                    ExpenseParticipantRepository.ExpenseShareView::getExpenseId,
                    ExpenseParticipantRepository.ExpenseShareView::getShareCents));

    List<ActivityItem> items = new ArrayList<>();
    for (ActivityExpenseView e : expenseRepository.findRecentByGroupIds(groupIds, limit)) {
      UserSummary payer =
          new UserSummary(e.getPayerId(), e.getPayerEmail(), e.getPayerDisplayName());
      items.add(
          new ActivityItem(
              e.getId(),
              ActivityItem.Kind.EXPENSE,
              e.getGroupId(),
              e.getGroupName(),
              e.getDescription(),
              payer,
              null,
              e.getAmountCents(),
              ExpenseService.viewerDelta(
                  userId, e.getPayerId(), e.getAmountCents(), myShare.getOrDefault(e.getId(), 0L)),
              e.getCategory(),
              e.getSpentOn(),
              e.getCurrency()));
    }
    paymentRepository
        .findRecentByGroupIds(groupIds, limit)
        .forEach(
            p ->
                items.add(
                    new ActivityItem(
                        p.getId(),
                        ActivityItem.Kind.PAYMENT,
                        p.getGroup().getId(),
                        p.getGroup().getName(),
                        "Settlement",
                        UserSummary.from(p.getPayer()),
                        UserSummary.from(p.getPayee()),
                        p.getAmountCents(),
                        0L,
                        null,
                        p.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        p.getCurrency())));

    // Merge both streams and keep the most recent by spent/settled date.
    items.sort(Comparator.comparing(ActivityItem::date).reversed());
    return items.stream().limit(ACTIVITY_LIMIT).toList();
  }
}
