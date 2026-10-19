package com.spliteasy.service;

import com.spliteasy.entity.Group;
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

  /** Called inside READ COMMITTED writes; membership may change while the group lock waits. */
  public Group lockForMember(UUID groupId, UUID requesterId) {
    membershipGuard.requireMember(groupId, requesterId);
    var group = groupRepository.findByIdForUpdate(groupId);
    // Recheck even when a concurrent group deletion made the locking lookup return no row.
    membershipGuard.requireMember(groupId, requesterId);
    return group.orElseThrow(() -> new NotFoundException("Group not found"));
  }
}
