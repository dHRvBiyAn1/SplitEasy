package com.spliteasy.service;

import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.repository.GroupMembershipRepository;

import lombok.RequiredArgsConstructor;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the group-membership authorization check. Every
 * group-scoped service calls this before touching group data, so a change to the
 * authorization rule only needs to happen here.
 */
@Component
@RequiredArgsConstructor
public class MembershipGuard {

    private final GroupMembershipRepository membershipRepository;

    /** Throws {@link ForbiddenException} (→ 403) if {@code userId} is not a member of {@code groupId}. */
    public void requireMember(UUID groupId, UUID userId) {
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("You are not a member of this group");
        }
    }
}
