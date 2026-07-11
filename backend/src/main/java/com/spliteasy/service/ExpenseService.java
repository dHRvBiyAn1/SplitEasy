package com.spliteasy.service;

import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.ExpenseResponse;
import com.spliteasy.dto.ExpenseSummary;
import com.spliteasy.dto.SplitInput;
import com.spliteasy.dto.SplitShare;
import com.spliteasy.dto.UserSummary;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.SplitType;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.service.split.Share;
import com.spliteasy.service.split.SplitContext;
import com.spliteasy.service.split.SplitStrategy;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExpenseService {

    private final ExpenseRepository expenseRepository;
    private final ExpenseParticipantRepository participantRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final MembershipGuard membershipGuard;
    /** Split-type → strategy, built from the strategy beans. No if/else on split type here. */
    private final Map<SplitType, SplitStrategy> strategies;

    public ExpenseService(
            ExpenseRepository expenseRepository,
            ExpenseParticipantRepository participantRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            UserRepository userRepository,
            MembershipGuard membershipGuard,
            List<SplitStrategy> splitStrategies) {
        this.expenseRepository = expenseRepository;
        this.participantRepository = participantRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.membershipGuard = membershipGuard;
        this.strategies = splitStrategies.stream()
                .collect(Collectors.toMap(SplitStrategy::type, Function.identity()));
    }

    @Transactional
    public ExpenseResponse createExpense(UUID requesterId, UUID groupId, CreateExpenseRequest request) {
        membershipGuard.requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

        ComputedSplit split = computeSplit(groupId, request);
        Expense expense = new Expense(
                group, request.description().trim(), request.amountCents(),
                split.usersById().get(split.payerId()), split.splitType(),
                categoryOf(request), spentOnOf(request));
        addShares(expense, split);
        expenseRepository.save(expense);
        return toResponse(expense);
    }

    @Transactional
    public ExpenseResponse updateExpense(
            UUID requesterId, UUID groupId, UUID expenseId, CreateExpenseRequest request) {
        membershipGuard.requireMember(groupId, requesterId);
        Expense expense = expenseRepository.findByIdFetchAll(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        if (!expense.getGroup().getId().equals(groupId)) {
            throw new NotFoundException("Expense not found in this group");
        }

        // Full replace: recompute shares from the new state via the same strategy path — never stale.
        ComputedSplit split = computeSplit(groupId, request);
        expense.setDescription(request.description().trim());
        expense.setAmountCents(request.amountCents());
        expense.setPaidBy(split.usersById().get(split.payerId()));
        expense.setSplitType(split.splitType());
        expense.setCategory(categoryOf(request));
        expense.setSpentOn(spentOnOf(request));
        // Flush the orphan-removal deletes before re-inserting, or the new rows collide with the
        // old ones on the (expense_id, user_id) unique constraint within one flush.
        expense.clearParticipants();
        expenseRepository.flush();
        addShares(expense, split);
        expenseRepository.save(expense);
        return toResponse(expense);
    }

    @Transactional
    public void deleteExpense(UUID requesterId, UUID groupId, UUID expenseId) {
        membershipGuard.requireMember(groupId, requesterId);
        Expense expense = expenseRepository.findById(expenseId)
                .orElseThrow(() -> new NotFoundException("Expense not found"));
        if (!expense.getGroup().getId().equals(groupId)) {
            throw new NotFoundException("Expense not found in this group");
        }
        expenseRepository.delete(expense); // cascade/orphanRemoval deletes expense_participants
    }

    /** Everything needed to write an expense's split — computed once, reused by create and update. */
    private record ComputedSplit(SplitType splitType, UUID payerId, List<Share> shares, Map<UUID, User> usersById) {}

    /** Validates payer/participants (membership) and computes shares via the split strategy. */
    private ComputedSplit computeSplit(UUID groupId, CreateExpenseRequest request) {
        Set<UUID> memberIds = new LinkedHashSet<>(membershipRepository.findUserIdsByGroupId(groupId));
        UUID payerId = request.paidByUserId();
        if (!memberIds.contains(payerId)) {
            throw new BadRequestException("The payer must be a member of this group");
        }
        SplitType splitType = Objects.requireNonNullElse(request.splitType(), SplitType.EQUAL);
        // Service resolves group membership; the strategy owns the split math + its validation.
        SplitContext ctx;
        if (splitType == SplitType.EQUAL) {
            ctx = SplitContext.forEqual(
                    request.amountCents(), payerId, resolveParticipants(request.participantUserIds(), memberIds));
        } else {
            ctx = SplitContext.forSplits(
                    request.amountCents(), payerId, requireSplits(request.splits(), memberIds));
        }
        List<Share> shares = strategies.get(splitType).split(ctx);
        Map<UUID, User> usersById = loadUsers(shares.stream().map(Share::userId).toList(), payerId);
        return new ComputedSplit(splitType, payerId, shares, usersById);
    }

    private ExpenseCategory categoryOf(CreateExpenseRequest request) {
        return request.category() != null ? request.category() : ExpenseCategory.OTHER;
    }

    private LocalDate spentOnOf(CreateExpenseRequest request) {
        return request.spentOn() != null ? request.spentOn() : LocalDate.now();
    }

    private void addShares(Expense expense, ComputedSplit split) {
        for (Share share : split.shares()) {
            expense.addParticipant(split.usersById().get(share.userId()), share.shareCents());
        }
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
        membershipGuard.requireMember(groupId, requesterId);
        // The requester's own share of each expense in the group — drives the "you lent / you
        // borrowed" delta without an N+1 (one query for the whole group).
        Map<UUID, Long> myShare = participantRepository
                .findSharesByUserAndGroups(requesterId, List.of(groupId)).stream()
                .collect(Collectors.toMap(
                        ExpenseParticipantRepository.ExpenseShareView::getExpenseId,
                        ExpenseParticipantRepository.ExpenseShareView::getShareCents));
        return expenseRepository.findSummariesByGroupId(groupId).stream()
                .map(v -> new ExpenseSummary(
                        v.getId(),
                        v.getDescription(),
                        v.getAmountCents(),
                        new UserSummary(v.getPayerId(), v.getPayerEmail(), v.getPayerDisplayName()),
                        v.getParticipantCount(),
                        v.getCategory(),
                        v.getSpentOn(),
                        viewerDelta(requesterId, v.getPayerId(), v.getAmountCents(), myShare.getOrDefault(v.getId(), 0L)),
                        v.getCreatedAt()))
                .toList();
    }

    /**
     * The requesting member's net for one expense: positive = they lent (paid more than their
     * share), negative = they borrowed (their share), 0 = not involved. Shared by the group
     * expense list and the dashboard activity feed so both read the same everywhere.
     */
    static long viewerDelta(UUID viewerId, UUID payerId, long amountCents, long viewerShareCents) {
        long paid = viewerId.equals(payerId) ? amountCents : 0L;
        return paid - viewerShareCents;
    }

    @Transactional(readOnly = true)
    public ExpenseResponse getExpense(UUID requesterId, UUID groupId, UUID expenseId) {
        membershipGuard.requireMember(groupId, requesterId);
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
                expense.getCategory(),
                expense.getSpentOn(),
                UserSummary.from(expense.getPaidBy()),
                shares,
                expense.getCreatedAt());
    }
}
