package com.spliteasy.service;

import com.spliteasy.dto.CreateGroupRequest;
import com.spliteasy.dto.GroupResponse;
import com.spliteasy.dto.GroupSummary;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.GroupMembership;
import com.spliteasy.entity.GroupType;
import com.spliteasy.entity.User;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.util.Emails;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupService {

    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final MembershipGuard membershipGuard;

    public GroupService(
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            UserRepository userRepository,
            MembershipGuard membershipGuard) {
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.membershipGuard = membershipGuard;
    }

    @Transactional
    public GroupResponse createGroup(UUID requesterId, CreateGroupRequest request) {
        User creator = userRepository.findById(requesterId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        GroupType type = request.type() != null ? request.type() : GroupType.OTHER;
        Group group = groupRepository.save(new Group(request.name().trim(), type, creator));
        membershipRepository.save(new GroupMembership(group, creator));
        // One code path for the DTO: toGroupResponse re-reads members (just the creator here).
        return toGroupResponse(group);
    }

    @Transactional
    public GroupResponse addMember(UUID requesterId, UUID groupId, String email) {
        // Membership check first (matches ExpenseService/BalanceService/PaymentService):
        // a non-member gets 403 without learning whether the group exists.
        membershipGuard.requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

        User invitee = userRepository.findByEmail(Emails.normalize(email))
                .orElseThrow(() -> new NotFoundException("No user found with that email"));
        if (membershipRepository.existsByGroupIdAndUserId(groupId, invitee.getId())) {
            throw new ConflictException("User is already a member of this group");
        }
        membershipRepository.save(new GroupMembership(group, invitee));
        return toGroupResponse(group);
    }

    @Transactional(readOnly = true)
    public List<GroupSummary> listMyGroups(UUID requesterId) {
        return membershipRepository.findGroupSummariesForUser(requesterId).stream()
                .map(v -> new GroupSummary(v.getId(), v.getName(), v.getType(), v.getMemberCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(UUID requesterId, UUID groupId) {
        // Membership check first: a non-member gets 403 without learning whether
        // the group exists (consistent with the other group-scoped services).
        membershipGuard.requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));
        return toGroupResponse(group);
    }

    /** Builds the full response, fetching members with their users in a single query. */
    private GroupResponse toGroupResponse(Group group) {
        List<UserSummary> members = membershipRepository.findByGroupIdFetchUser(group.getId()).stream()
                .map(m -> UserSummary.from(m.getUser()))
                .toList();
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getType(),
                UserSummary.from(group.getCreatedBy()),
                members,
                group.getCreatedAt());
    }
}
