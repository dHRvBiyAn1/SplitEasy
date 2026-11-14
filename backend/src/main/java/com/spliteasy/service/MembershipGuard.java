package com.spliteasy.service;

import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the group-membership authorization check. Every group-scoped service
 * calls this before touching group data, so a change to the authorization rule only needs to happen
 * here.
 */
@Component
@RequiredArgsConstructor
public class MembershipGuard {

  private final GroupMembershipRepository membershipRepository;
  private final GroupRepository groupRepository;

  /**
   * Throws {@link ForbiddenException} (→ 403) if {@code userId} is not a member of {@code groupId},
   * then {@link NotFoundException} (→ 404) if the group is recoverably deleted (migration V19).
   * Ordering keeps 403 before 404, so a non-member never learns whether a group exists.
   */
  public void requireMember(UUID groupId, UUID userId) {
    requireMemberOfAnyState(groupId, userId);
    if (groupRepository.existsByIdAndDeletedAtIsNotNull(groupId)) {
      throw new NotFoundException("Group not found");
    }
  }

  /**
   * Membership only, ignoring a deletion marker — for the recovery list and restore, the operations
   * that must still reach a deleted group.
   */
  public void requireMemberOfAnyState(UUID groupId, UUID userId) {
    if (!membershipRepository.existsByGroupIdAndUserId(groupId, userId)) {
      throw new ForbiddenException("You are not a member of this group");
    }
  }
}
