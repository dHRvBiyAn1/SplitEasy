package com.spliteasy.service;

import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.ExpenseResponse;
import com.spliteasy.dto.ExpenseSummary;
import com.spliteasy.dto.SplitShare;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.entity.Group;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public ExpenseService(
            ExpenseRepository expenseRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            UserRepository userRepository) {
        this.expenseRepository = expenseRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ExpenseResponse createExpense(UUID requesterId, UUID groupId, CreateExpenseRequest request) {
        requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

        Set<UUID> memberIds = new LinkedHashSet<>(membershipRepository.findUserIdsByGroupId(groupId));

        UUID payerId = request.paidByUserId();
        if (!memberIds.contains(payerId)) {
            throw new BadRequestException("The payer must be a member of this group");
        }

        List<UUID> participantIds = resolveParticipants(request.participantUserIds(), memberIds);

        // Core split math — pure and unit-tested separately.
        List<ExpenseSplitCalculator.Share> shares =
                ExpenseSplitCalculator.splitEqually(request.amountCents(), participantIds, payerId);

        Map<UUID, User> usersById = loadUsers(participantIds, payerId);
        Expense expense = new Expense(group, request.description().trim(), request.amountCents(), usersById.get(payerId));
        for (ExpenseSplitCalculator.Share share : shares) {
            expense.addParticipant(usersById.get(share.userId()), share.shareCents());
        }
        expenseRepository.save(expense);
        return toResponse(expense);
    }

    @Transactional(readOnly = true)
    public List<ExpenseSummary> listExpenses(UUID requesterId, UUID groupId) {
        requireMember(groupId, requesterId);
        return expenseRepository.findSummariesByGroupId(groupId).stream()
                .map(v -> new ExpenseSummary(
                        v.getId(),
                        v.getDescription(),
                        v.getAmountCents(),
                        new UserSummary(v.getPayerId(), v.getPayerEmail(), v.getPayerDisplayName()),
                        v.getParticipantCount(),
                        v.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExpenseResponse getExpense(UUID requesterId, UUID groupId, UUID expenseId) {
        requireMember(groupId, requesterId);
        Expense expense = expenseRepository.findByIdFetchAll(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        if (!expense.getGroup().getId().equals(groupId)) {
            throw new NotFoundException("Expense not found in this group");
        }
        return toResponse(expense);
    }

    private List<UUID> resolveParticipants(List<UUID> requested, Set<UUID> memberIds) {
        if (requested == null || requested.isEmpty()) {
            // Default: everyone in the group shares the expense.
            return List.copyOf(memberIds);
        }
        List<UUID> distinct = requested.stream().distinct().toList();
        for (UUID id : distinct) {
            if (!memberIds.contains(id)) {
                throw new BadRequestException("Participant " + id + " is not a member of this group");
            }
        }
        return distinct;
    }

    private Map<UUID, User> loadUsers(List<UUID> participantIds, UUID payerId) {
        Set<UUID> ids = new LinkedHashSet<>(participantIds);
        ids.add(payerId);
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private void requireMember(UUID groupId, UUID userId) {
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("You are not a member of this group");
        }
    }

    private ExpenseResponse toResponse(Expense expense) {
        List<SplitShare> shares = expense.getParticipants().stream()
                .map(p -> new SplitShare(UserSummary.from(p.getUser()), p.getShareCents()))
                .sorted(Comparator.comparing(s -> s.user().displayName()))
                .toList();
        return new ExpenseResponse(
                expense.getId(),
                expense.getGroup().getId(),
                expense.getDescription(),
                expense.getAmountCents(),
                UserSummary.from(expense.getPaidBy()),
                shares,
                expense.getCreatedAt());
    }
}
