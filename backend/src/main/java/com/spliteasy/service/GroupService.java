package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.dto.group.GroupSummary;
import com.spliteasy.dto.group.UpdateGroupRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.GroupMembership;
import com.spliteasy.entity.GroupType;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.util.Emails;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupService {

  private final FinancialParticipantService financialParticipants;
  private final CreateOperations createOperations;
  private final GroupRepository groupRepository;
  private final GroupMembershipRepository membershipRepository;
  private final UserRepository userRepository;
  private final MembershipGuard membershipGuard;
  private final GroupWriteGuard groupWriteGuard;

  /** Reused for the settled-up guard on remove/leave — never recompute balances here. */
  private final BalanceService balanceService;

  private final PairwiseBalanceService pairwiseBalanceService;
  private final ObligationProjectionService projection;
  private final com.spliteasy.repository.PersonalLedgerRepository personalLedgers;
  private final com.spliteasy.activity.ActivityRecorder activity;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse createGroup(UUID requesterId, CreateGroupRequest request) {
    return createOperations.create(
        requesterId,
        "group",
        requesterId,
        request.operationId(),
        new CreateGroupRequest(
            request.name().trim(), request.type() == null ? GroupType.OTHER : request.type()),
        GroupResponse.class,
        response -> membershipGuard.requireMember(response.id(), requesterId),
        () -> createNewGroup(requesterId, request));
  }

  private GroupResponse createNewGroup(UUID requesterId, CreateGroupRequest request) {
    User creator =
        userRepository
            .findById(requesterId)
            .orElseThrow(() -> new NotFoundException("User not found"));
    GroupType type = request.type() != null ? request.type() : GroupType.OTHER;
    Group group = groupRepository.save(new Group(request.name().trim(), type, creator));
    membershipRepository.saveAndFlush(new GroupMembership(group, creator));
    financialParticipants.activate(group.getId(), requesterId);
    log.info("Group {} '{}' created by user {}", group.getId(), group.getName(), requesterId);
    // One code path for the DTO: toGroupResponse re-reads members (just the creator here).
    return toGroupResponse(group, requesterId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse addMember(UUID requesterId, UUID groupId, String email) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);

    User invitee =
        userRepository
            .findByEmail(Emails.normalize(email))
            .orElseThrow(() -> new NotFoundException("No user found with that email"));
    if (membershipRepository.existsByGroupIdAndUserId(groupId, invitee.getId())) {
      throw new ConflictException("User is already a member of this group");
    }
    membershipRepository.saveAndFlush(new GroupMembership(group, invitee));
    financialParticipants.activate(groupId, invitee.getId());
    activity.group(
        groupId, "MEMBER_ADDED", requesterId, group.getName(), UserSummary.from(invitee));
    log.info("User {} added member {} to group {}", requesterId, invitee.getId(), groupId);
    return toGroupResponse(group, requesterId);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<GroupSummary> listMyGroups(UUID requesterId) {
    // A personal ledger (V21) is never a group; its money reaches friend and dashboard totals.
    var personal = personalLedgers.ledgersOf(requesterId);
    return membershipRepository.findGroupSummariesForUser(requesterId).stream()
        .filter(v -> !personal.contains(v.getId()))
        .map(
            v ->
                new GroupSummary(
                    v.getId(),
                    v.getName(),
                    v.getType(),
                    v.getMemberCount(),
                    v.getSimplifyDebts(),
                    v.getArchivedAt(),
                    v.getLifecycleVersion()))
        .toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public GroupResponse getGroup(UUID requesterId, UUID groupId) {
    // Membership check first: a non-member gets 403 without learning whether
    // the group exists (consistent with the other group-scoped services).
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Group not found"));
    return toGroupResponse(group, requesterId);
  }

  /**
   * Partial update of the group's settings (name / type / simplify-debts). Any member may edit,
   * matching the flat authorization elsewhere; a null field is left untouched.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse updateGroup(UUID requesterId, UUID groupId, UpdateGroupRequest request) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    // Settings stay editable while archived: renaming or re-typing a group is not a new entry.
    Group group =
        request.simplifyDebts() != null
            ? groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId)
            : groupRepository
                .findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

    // Preserve the loaded dirty-field semantics, but serialize roster compatibility before writes.
    if (request.simplifyDebts() == null)
      groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);

    if (request.simplifyDebts() != null && request.simplifyDebts() != group.isSimplifyDebts()) {
      var active =
          financialParticipants.roster(groupId).stream()
              .map(com.spliteasy.dto.common.ParticipantSummary::id)
              .collect(java.util.stream.Collectors.toSet());
      boolean formerExposure =
          projection.snapshot(requesterId, groupId, request.simplifyDebts()).stream()
              .anyMatch(d -> !active.contains(d.from().id()) || !active.contains(d.to().id()));
      if (formerExposure) {
        throw new ConflictException(
            "Re-add former participants and repair their obligations before changing debt mode");
      }
    }

    if (request.name() != null) {
      String name = request.name().trim();
      if (name.isEmpty()) {
        throw new BadRequestException("Group name cannot be blank");
      }
      group.setName(name);
    }
    if (request.type() != null) {
      group.setType(request.type());
    }
    if (request.simplifyDebts() != null) {
      group.setSimplifyDebts(request.simplifyDebts());
    }
    log.info("Group {} settings updated by user {}", groupId, requesterId);
    return toGroupResponse(group, requesterId);
  }

  /**
   * Removes a member — or the requester themselves, which is "leave group". Blocked while the
   * member still has a non-zero net or current-mode obligation in the group. The creator can't be
   * removed or leave: they own the group and delete it instead.
   */
  // The group lock keeps financial inputs stable while nested aggregate reads join this write.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void removeMember(UUID requesterId, UUID groupId, UUID targetUserId) {
    // Leaving an archived group must stay possible: departure is not a new financial entry.
    Group group = groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    if (!membershipRepository.existsByGroupIdAndUserId(groupId, targetUserId)) {
      throw new NotFoundException("That person is not a member of this group");
    }

    boolean isSelf = targetUserId.equals(requesterId);
    if (group.getCreatedBy().getId().equals(targetUserId)) {
      throw new ConflictException(
          isSelf
              ? "You created this group — delete it instead of leaving"
              : "The group creator can't be removed");
    }

    if (hasNonzeroBalance(requesterId, groupId, targetUserId)
        || !pairwiseBalanceService.compute(targetUserId, groupId).isEmpty()) {
      throw new ConflictException(
          isSelf
              ? "Settle your outstanding obligations before leaving this group"
              : "That member still has outstanding obligations in this group");
    }

    membershipRepository.deleteByGroupIdAndUserId(groupId, targetUserId);
    financialParticipants.deactivate(groupId, targetUserId);
    activity.group(
        groupId,
        isSelf ? "MEMBER_LEFT" : "MEMBER_REMOVED",
        requesterId,
        group.getName(),
        userRepository.findById(targetUserId).map(UserSummary::from).orElse(null));
    log.info("User {} removed member {} from group {}", requesterId, targetUserId, groupId);
  }

  /**
   * Archives the group: reads, history and export keep working, new financial entries do not. Any
   * member may archive, matching the flat settings authorization.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse archive(UUID requesterId, UUID groupId) {
    Group group = groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    if (group.isArchived()) {
      throw new ConflictException("This group is already archived");
    }
    group.archive();
    activity.group(groupId, "GROUP_ARCHIVED", requesterId, group.getName(), null);
    log.info("Group {} archived by user {}", groupId, requesterId);
    return toGroupResponse(group, requesterId);
  }

  /** Re-opens an archived group for new entries. Any member, mirroring archive. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse unarchive(UUID requesterId, UUID groupId) {
    Group group = groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    if (!group.isArchived()) {
      throw new ConflictException("This group is not archived");
    }
    group.unarchive();
    activity.group(groupId, "GROUP_UNARCHIVED", requesterId, group.getName(), null);
    log.info("Group {} re-opened by user {}", groupId, requesterId);
    return toGroupResponse(group, requesterId);
  }

  /**
   * Recoverable deletion, creator only — the group's admin. Nothing financial is destroyed: the
   * marker hides the group from every ordinary read while expenses, payments and history stay
   * intact for restore. Blocked while anyone still owes anything, so a settled-up decision is never
   * hidden behind a deletion.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteGroup(UUID requesterId, UUID groupId) {
    Group group = groupWriteGuard.lockForMemberAllowingArchived(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    requireCreator(group, requesterId, "delete");
    requireSettled(requesterId, groupId, group);
    group.markDeleted();
    activity.group(groupId, "GROUP_DELETED", requesterId, group.getName(), null);
    log.info("Group {} deleted by creator {} (recoverable)", groupId, requesterId);
  }

  /** The creator's own recovery list: their deleted groups, newest first. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<GroupSummary> listDeletedGroups(UUID requesterId) {
    return groupRepository.findDeletedByCreator(requesterId).stream()
        .map(
            g ->
                new GroupSummary(
                    g.getId(),
                    g.getName(),
                    g.getType(),
                    membershipRepository.countByGroupId(g.getId()),
                    g.isSimplifyDebts(),
                    g.getArchivedAt(),
                    g.getLifecycleVersion()))
        .toList();
  }

  /**
   * Restores a deleted group at its expected lifecycle version, so a stale recovery screen cannot
   * resurrect a group whose state has moved on. Creator only, like deletion.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse restore(UUID requesterId, UUID groupId, long expectedVersion) {
    Group group = groupWriteGuard.lockForLifecycle(groupId, requesterId);
    requireCreator(group, requesterId, "restore");
    if (!group.isDeleted()) {
      throw new ConflictException("This group is not deleted");
    }
    if (group.getLifecycleVersion() != expectedVersion) {
      throw new ConflictException(
          "This group changed since you loaded it — reload the recovery list and try again");
    }
    group.restore();
    financialParticipants.requireCompatible(groupId);
    activity.group(groupId, "GROUP_RESTORED", requesterId, group.getName(), null);
    log.info("Group {} restored by creator {}", groupId, requesterId);
    return toGroupResponse(group, requesterId);
  }

  /** Creator-only rule, checked after membership so a non-member still gets 403 first. */
  private void requireCreator(Group group, UUID requesterId, String action) {
    if (!group.getCreatedBy().getId().equals(requesterId)) {
      throw new ForbiddenException("Only the group's creator can " + action + " it");
    }
  }

  /**
   * Deletion requires a fully settled group: no canonical obligation for the selected mode and no
   * non-zero net in any currency. Both come from the shared ledger reads, exactly as the
   * member-departure guard uses them — never a hand-rolled balance sum.
   */
  private void requireSettled(UUID requesterId, UUID groupId, Group group) {
    var outstanding = projection.snapshot(requesterId, groupId, group.isSimplifyDebts());
    if (!outstanding.isEmpty()) {
      var first = outstanding.get(0);
      throw new ConflictException(
          "Settle this group before deleting it — "
              + first.from().displayName()
              + " still owes "
              + first.to().displayName()
              + (outstanding.size() > 1 ? " and " + (outstanding.size() - 1) + " more" : ""));
    }
    boolean openNet =
        balanceService.computeBalances(requesterId, groupId).balances().stream()
            .anyMatch(b -> b.netMinor() != 0);
    if (openNet) {
      throw new ConflictException("Settle every member's balance before deleting this group");
    }
  }

  /** That member's net in the group, read through the shared balance aggregation. */
  private boolean hasNonzeroBalance(UUID requesterId, UUID groupId, UUID targetUserId) {
    return balanceService.computeBalances(requesterId, groupId).balances().stream()
        .filter(b -> b.user().id().equals(targetUserId))
        .anyMatch(b -> b.netMinor() != 0);
  }

  /** Builds the full response, fetching members with their users in a single query. */
  private GroupResponse toGroupResponse(Group group, UUID requesterId) {
    List<UserSummary> members =
        membershipRepository.findByGroupIdFetchUser(group.getId()).stream()
            .map(m -> UserSummary.from(m.getUser()))
            .toList();
    return new GroupResponse(
        group.getId(),
        group.getName(),
        group.getType(),
        UserSummary.from(group.getCreatedBy()),
        members,
        group.isSimplifyDebts(),
        group.getCreatedAt(),
        financialParticipants.roster(group.getId()),
        requesterId,
        financialParticipants.originals(group.getId()).values().stream()
            .filter(p -> requesterId.equals(p.accountId()))
            .map(com.spliteasy.dto.common.ParticipantSummary::id)
            .sorted()
            .toList(),
        financialParticipants.inactiveGuests(group.getId()),
        group.getArchivedAt(),
        group.getLifecycleVersion());
  }
}
