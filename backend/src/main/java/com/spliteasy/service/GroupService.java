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
    membershipRepository.save(new GroupMembership(group, creator));
    log.info("Group {} '{}' created by user {}", group.getId(), group.getName(), requesterId);
    // One code path for the DTO: toGroupResponse re-reads members (just the creator here).
    return toGroupResponse(group);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse addMember(UUID requesterId, UUID groupId, String email) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);

    User invitee =
        userRepository
            .findByEmail(Emails.normalize(email))
            .orElseThrow(() -> new NotFoundException("No user found with that email"));
    if (membershipRepository.existsByGroupIdAndUserId(groupId, invitee.getId())) {
      throw new ConflictException("User is already a member of this group");
    }
    membershipRepository.save(new GroupMembership(group, invitee));
    log.info("User {} added member {} to group {}", requesterId, invitee.getId(), groupId);
    return toGroupResponse(group);
  }

  @Transactional(readOnly = true)
  public List<GroupSummary> listMyGroups(UUID requesterId) {
    return membershipRepository.findGroupSummariesForUser(requesterId).stream()
        .map(
            v ->
                new GroupSummary(
                    v.getId(), v.getName(), v.getType(), v.getMemberCount(), v.getSimplifyDebts()))
        .toList();
  }

  @Transactional(readOnly = true)
  public GroupResponse getGroup(UUID requesterId, UUID groupId) {
    // Membership check first: a non-member gets 403 without learning whether
    // the group exists (consistent with the other group-scoped services).
    membershipGuard.requireMember(groupId, requesterId);
    Group group =
        groupRepository
            .findById(groupId)
            .orElseThrow(() -> new NotFoundException("Group not found"));
    return toGroupResponse(group);
  }

  /**
   * Partial update of the group's settings (name / type / simplify-debts). Any member may edit,
   * matching the flat authorization elsewhere; a null field is left untouched.
   */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public GroupResponse updateGroup(UUID requesterId, UUID groupId, UpdateGroupRequest request) {
    membershipGuard.requireMember(groupId, requesterId);
    Group group =
        request.simplifyDebts() != null
            ? groupWriteGuard.lockForMember(groupId, requesterId)
            : groupRepository
                .findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

    if (request.simplifyDebts() != null && request.simplifyDebts() != group.isSimplifyDebts()) {
      var active =
          membershipRepository.findByGroupIdFetchUser(groupId).stream()
              .map(m -> m.getUser().getId())
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
    return toGroupResponse(group);
  }

  /**
   * Removes a member — or the requester themselves, which is "leave group". Blocked while the
   * member still has a non-zero net or current-mode obligation in the group. The creator can't be
   * removed or leave: they own the group and delete it instead.
   */
  // The group lock keeps financial inputs stable while nested aggregate reads join this write.
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void removeMember(UUID requesterId, UUID groupId, UUID targetUserId) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);
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
    log.info("User {} removed member {} from group {}", requesterId, targetUserId, groupId);
  }

  /** Deletes the group and everything under it. Creator only — the group's admin. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteGroup(UUID requesterId, UUID groupId) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);
    if (!group.getCreatedBy().getId().equals(requesterId)) {
      throw new ForbiddenException("Only the group's creator can delete it");
    }
    // Memberships, expenses (+participants) and payments go with it via ON DELETE CASCADE.
    groupRepository.delete(group);
    log.info("Group {} deleted by creator {}", groupId, requesterId);
  }

  /** That member's net in the group, read through the shared balance aggregation. */
  private boolean hasNonzeroBalance(UUID requesterId, UUID groupId, UUID targetUserId) {
    return balanceService.computeBalances(requesterId, groupId).balances().stream()
        .filter(b -> b.user().id().equals(targetUserId))
        .anyMatch(b -> b.netMinor() != 0);
  }

  /** Builds the full response, fetching members with their users in a single query. */
  private GroupResponse toGroupResponse(Group group) {
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
        group.getCreatedAt());
  }
}
