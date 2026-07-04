package com.spliteasy.service;

import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.ExpenseResponse;
import com.spliteasy.dto.ExpenseSummary;
import com.spliteasy.dto.SplitInput;
import com.spliteasy.dto.SplitShare;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.SplitType;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

        SplitType splitType = request.splitType() == null ? SplitType.EQUAL : request.splitType();
        // Split math (pure, unit-tested separately). Whatever the type, we end up with a list
        // of per-participant share_cents that sum to amountCents — everything downstream is shared.
        List<ExpenseSplitCalculator.Share> shares = computeShares(request, splitType, memberIds, payerId);

        List<UUID> participantIds = shares.stream().map(ExpenseSplitCalculator.Share::userId).toList();
        Map<UUID, User> usersById = loadUsers(participantIds, payerId);
        Expense expense = new Expense(
                group, request.description().trim(), request.amountCents(), usersById.get(payerId), splitType);
        for (ExpenseSplitCalculator.Share share : shares) {
            expense.addParticipant(usersById.get(share.userId()), share.shareCents());
        }
        expenseRepository.save(expense);
        return toResponse(expense);
    }

    private List<ExpenseSplitCalculator.Share> computeShares(
            CreateExpenseRequest request, SplitType type, Set<UUID> memberIds, UUID payerId) {
        return switch (type) {
            case EQUAL -> ExpenseSplitCalculator.splitEqually(
                    request.amountCents(), resolveParticipants(request.participantUserIds(), memberIds), payerId);
            case UNEQUAL -> unequalShares(request, memberIds);
            case PERCENTAGE -> ExpenseSplitCalculator.splitByBasisPoints(
                    request.amountCents(), basisPointsByUser(request, memberIds), payerId);
        };
    }

    private List<ExpenseSplitCalculator.Share> unequalShares(CreateExpenseRequest request, Set<UUID> memberIds) {
        List<SplitInput> splits = requireSplits(request.splits(), memberIds);
        long sum = splits.stream().mapToLong(SplitInput::value).sum();
        if (sum != request.amountCents()) {
            throw new BadRequestException(
                    "Entered amounts (%dc) must add up to the expense total (%dc)"
                            .formatted(sum, request.amountCents()));
        }
        return splits.stream()
                .map(s -> new ExpenseSplitCalculator.Share(s.userId(), s.value()))
                .toList();
    }

    private Map<UUID, Long> basisPointsByUser(CreateExpenseRequest request, Set<UUID> memberIds) {
        List<SplitInput> splits = requireSplits(request.splits(), memberIds);
        // Sum-to-10000 is validated inside splitByBasisPoints.
        Map<UUID, Long> bp = new LinkedHashMap<>();
        for (SplitInput s : splits) {
            bp.put(s.userId(), s.value());
        }
        return bp;
    }

    /** A non-empty, duplicate-free split list where every user is a group member. */
    private List<SplitInput> requireSplits(List<SplitInput> splits, Set<UUID> memberIds) {
        if (splits == null || splits.isEmpty()) {
            throw new BadRequestException("This split type requires a per-participant split list");
        }
        Set<UUID> seen = new HashSet<>();
        for (SplitInput s : splits) {
            if (!memberIds.contains(s.userId())) {
                throw new BadRequestException("Participant " + s.userId() + " is not a member of this group");
            }
            if (!seen.add(s.userId())) {
                throw new BadRequestException("Duplicate participant in split: " + s.userId());
            }
        }
        return splits;
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
                expense.getSplitType(),
                UserSummary.from(expense.getPaidBy()),
                shares,
                expense.getCreatedAt());
    }
}
