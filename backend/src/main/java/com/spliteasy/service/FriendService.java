package com.spliteasy.service;

import com.spliteasy.config.ParticipantContract;
import com.spliteasy.dto.friend.FriendBalance;
import com.spliteasy.dto.friend.FriendDetail;
import com.spliteasy.dto.friend.FriendSummary;
import com.spliteasy.dto.friend.SaveFriendRequest;
import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.FriendRepository;
import com.spliteasy.repository.FriendRepository.SavedFriend;
import com.spliteasy.repository.GroupMembershipRepository;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendService {
  private final FriendRepository friends;
  private final GroupMembershipRepository memberships;
  private final FinancialParticipantService participants;
  private final GroupWriteGuard writes;
  private final BalanceService balances;
  private final PairwiseBalanceService pairwise;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<FriendSummary> list(UUID owner) {
    var saved = friends.findByOwner(owner);
    ParticipantContract.requireEnabled();
    var scopes =
        scopes(owner, saved.stream().map(SavedFriend::friendAccountId).collect(Collectors.toSet()));
    return saved.stream()
        .map(friend -> summary(friend, scopes.getOrDefault(friend.friendAccountId(), List.of())))
        .toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public FriendDetail detail(UUID owner, UUID id) {
    var saved =
        friends.find(owner, id).orElseThrow(() -> new NotFoundException("Saved friend not found"));
    ParticipantContract.requireEnabled();
    var shared = scopes(owner, Set.of(id)).getOrDefault(id, List.of());
    var summary = summary(saved, shared);
    return new FriendDetail(
        summary.id(),
        summary.displayName(),
        summary.createdAt(),
        summary.sharedGroupCount(),
        summary.hasOpenObligations(),
        summary.balances(),
        shared.stream()
            .map(
                g ->
                    new FriendDetail.SharedGroup(g.id(), g.name(), g.simplifyDebts(), g.balances()))
            .toList());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void remove(UUID owner, UUID id) {
    ParticipantContract.requireEnabled();
    friends.remove(owner, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public FriendSummary save(UUID owner, SaveFriendRequest request) {
    writes.lockForMember(request.groupId(), owner);
    ParticipantContract.requireEnabled();
    var original = participants.originals(request.groupId()).get(request.participantId());
    if (original == null
        || !participants.active(request.groupId()).contains(request.participantId()))
      throw new BadRequestException("Choose a current financial participant from this group.");
    if (original.accountId() == null)
      throw new BadRequestException(
          "This guest must explicitly claim their participant before you can save them as a friend.");
    UUID target = original.accountId();
    if (target.equals(owner))
      throw new BadRequestException("Choose another person to save as a friend.");
    var source = participants.originals(request.groupId()).getOrDefault(target, original);
    // Validate all response denominations and exact totals before persisting the bookmark.
    var shared = scopes(owner, Set.of(target)).getOrDefault(target, List.of());
    summary(new SavedFriend(target, source.displayName(), java.time.Instant.now()), shared);
    friends.save(owner, target, source.displayName());
    var saved =
        friends
            .find(owner, target)
            .orElseThrow(
                () ->
                    new ConflictException(
                        "This saved friend changed during the request. Reload and review your directory before saving again."));
    return summary(saved, shared);
  }

  private record SharedScope(
      UUID id, String name, boolean simplifyDebts, List<FriendBalance> balances) {}

  private Map<UUID, List<SharedScope>> scopes(UUID owner, Set<UUID> targets) {
    Map<UUID, List<SharedScope>> result = new HashMap<>();
    if (targets.isEmpty()) return result;
    // ponytail: one pass over the owner's groups; batch aggregates if group counts warrant it.
    for (var group : memberships.findGroupSummariesForUser(owner)) {
      var present =
          participants.originals(group.getId()).values().stream()
              .map(p -> p.canonicalParticipantId())
              .filter(targets::contains)
              .collect(Collectors.toSet());
      if (present.isEmpty()) continue;
      var currencies =
          balances.computeBalances(owner, group.getId()).balances().stream()
              .map(b -> b.currency())
              .collect(Collectors.toCollection(TreeSet::new));
      var obligations = pairwise.compute(owner, group.getId());
      for (UUID target : present) {
        Map<LedgerCurrency, Long> amounts = new EnumMap<>(LedgerCurrency.class);
        obligations.stream()
            .filter(p -> p.user().id().equals(target))
            .forEach(p -> amounts.merge(p.currency(), p.netMinor(), Math::addExact));
        var rows =
            currencies.stream()
                .map(c -> new FriendBalance(c, amounts.getOrDefault(c, 0L)))
                .toList();
        result
            .computeIfAbsent(target, ignored -> new ArrayList<>())
            .add(new SharedScope(group.getId(), group.getName(), group.getSimplifyDebts(), rows));
      }
    }
    return result;
  }

  private FriendSummary summary(SavedFriend saved, List<SharedScope> scopes) {
    Map<LedgerCurrency, Long> totals = new TreeMap<>();
    for (var group : scopes)
      for (var row : group.balances()) totals.merge(row.currency(), row.netMinor(), Math::addExact);
    return new FriendSummary(
        saved.friendAccountId(),
        saved.displayNameSnapshot(),
        saved.createdAt(),
        scopes.size(),
        scopes.stream().flatMap(g -> g.balances().stream()).anyMatch(b -> b.netMinor() != 0),
        totals.entrySet().stream().map(e -> new FriendBalance(e.getKey(), e.getValue())).toList());
  }
}
