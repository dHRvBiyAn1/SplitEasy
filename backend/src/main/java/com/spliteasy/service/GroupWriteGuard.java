package com.spliteasy.service;

import com.spliteasy.entity.Group;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GroupWriteGuard {
  private final GroupRepository groupRepository;
  private final MembershipGuard membershipGuard;

  /**
   * Called inside READ COMMITTED writes; membership may change while the group lock waits. An
   * archived group is read-only, so every entry path through here is rejected.
   */
  public Group lockForMember(UUID groupId, UUID requesterId) {
    Group group = lockForMemberAllowingArchived(groupId, requesterId);
    if (group.isArchived()) {
      throw new ConflictException("This group is archived — re-open it to add or change entries");
    }
    return group;
  }

  /**
   * For writes that must keep working on an archived group: leaving, settings edits and the
   * lifecycle operations. A deleted group is still 404 through the membership guard.
   */
  public Group lockForMemberAllowingArchived(UUID groupId, UUID requesterId) {
    membershipGuard.requireMember(groupId, requesterId);
    var group = groupRepository.findByIdForUpdate(groupId);
    // Recheck even when a concurrent group deletion made the locking lookup return no row.
    membershipGuard.requireMember(groupId, requesterId);
    Group locked = group.orElseThrow(() -> new NotFoundException("Group not found"));
    // Memberships survive a recoverable deletion, so the membership recheck alone would let a
    // write that waited for the deleting transaction land on a deleted group. Fail closed here
    // too, independently of the membership guard.
    if (locked.isDeleted()) {
      throw new NotFoundException("Group not found");
    }
    return locked;
  }

  /** Restore only: the one write whose purpose is to reach a recoverably deleted group. */
  public Group lockForLifecycle(UUID groupId, UUID requesterId) {
    membershipGuard.requireMemberOfAnyState(groupId, requesterId);
    var group = groupRepository.findByIdForUpdate(groupId);
    membershipGuard.requireMemberOfAnyState(groupId, requesterId);
    return group.orElseThrow(() -> new NotFoundException("Group not found"));
  }
}
