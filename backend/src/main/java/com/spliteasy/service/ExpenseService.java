package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseSummary;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.expense.SplitShare;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.SplitType;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.UserRepository;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ExpenseService {

  private final CreateOperations createOperations;
  private final ExpenseVersionPolicy versionPolicy;
  private final jakarta.persistence.EntityManager entityManager;
  private final ExpenseRepository expenseRepository;
  private final ExpenseParticipantRepository participantRepository;
  private final GroupWriteGuard groupWriteGuard;
  private final GroupMembershipRepository membershipRepository;
  private final UserRepository userRepository;
  private final MembershipGuard membershipGuard;

  /** Split-type → strategy, built from the strategy beans. No if/else on split type here. */
  private final Map<SplitType, SplitStrategy> strategies;

  public ExpenseService(
      CreateOperations createOperations,
      ExpenseVersionPolicy versionPolicy,
      jakarta.persistence.EntityManager entityManager,
      ExpenseRepository expenseRepository,
      ExpenseParticipantRepository participantRepository,
      GroupWriteGuard groupWriteGuard,
      GroupMembershipRepository membershipRepository,
      UserRepository userRepository,
      MembershipGuard membershipGuard,
      List<SplitStrategy> splitStrategies) {
    this.createOperations = createOperations;
    this.versionPolicy = versionPolicy;
    this.entityManager = entityManager;
    this.expenseRepository = expenseRepository;
    this.participantRepository = participantRepository;
    this.groupWriteGuard = groupWriteGuard;
    this.membershipRepository = membershipRepository;
    this.userRepository = userRepository;
    this.membershipGuard = membershipGuard;
    this.strategies =
        splitStrategies.stream()
            .collect(Collectors.toMap(SplitStrategy::type, Function.identity()));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse createExpense(
      UUID requesterId, UUID groupId, CreateExpenseRequest request) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);
    com.spliteasy.config.LedgerContract.requireCurrency(request.resolvedCurrency());
    return createOperations.create(
        requesterId,
        "expense",
        groupId,
        request.operationId(),
        new CreateExpenseRequest(
            request.description().trim(),
            request.amountCents(),
            request.paidByUserId(),
            request.participantUserIds() == null
                ? List.of()
                : request.participantUserIds().stream().distinct().sorted().toList(),
            request.splitType() == null ? SplitType.EQUAL : request.splitType(),
            request.splits() == null
                ? null
                : request.splits().stream()
                    .sorted(Comparator.comparing(SplitInput::userId))
                    .toList(),
            categoryOf(request),
            request.spentOn(),
            null,
            null,
            request.amountMinor(),
            request.currency()),
        ExpenseResponse.class,
        response -> {},
        () -> createNewExpense(requesterId, groupId, group, request));
  }

  private ExpenseResponse createNewExpense(
      UUID requesterId, UUID groupId, Group group, CreateExpenseRequest request) {
    log.debug("Creating expense in group {} by user {}", groupId, requesterId);

    ComputedSplit split = computeSplit(groupId, request);
    Expense expense =
        new Expense(
            group,
            request.description().trim(),
            request.resolvedAmountMinor(),
            split.usersById().get(split.payerId()),
            split.splitType(),
            categoryOf(request),
            spentOnOf(request));
    expense.setCurrency(request.resolvedCurrency());
    addShares(expense, split);
    expenseRepository.save(expense);
    log.info(
        "Created {} expense {} ({} cents) in group {} paid by {}",
        split.splitType(),
        expense.getId(),
        expense.getAmountCents(),
        groupId,
        split.payerId());
    return toResponse(expense);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse updateExpense(
      UUID requesterId, UUID groupId, UUID expenseId, CreateExpenseRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    if (!expense.getGroup().getId().equals(groupId)) {
      throw new NotFoundException("Expense not found in this group");
    }

    com.spliteasy.config.LedgerContract.requireCurrency(expense.getCurrency());
    if (expense.getCurrency() != request.resolvedCurrency()) {
      throw new BadRequestException(
          "Expense currency cannot change. Create a separate expense in the correct currency.");
    }
    versionPolicy.requireExpected(request.expectedVersion(), expense.getVersion());
    requireActiveExpenseParticipants(expense);

    // Validate before advancing; parent versions must also cover child-only split intent changes.
    ComputedSplit split = computeSplit(groupId, request);
    // Immediate increment (unlike deferred OPTIMISTIC_FORCE_INCREMENT) keeps the response current.
    entityManager.lock(expense, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    expense.setDescription(request.description().trim());
    expense.setAmountCents(request.resolvedAmountMinor());
    expense.setPaidBy(split.usersById().get(split.payerId()));
    expense.setSplitType(split.splitType());
    expense.setCategory(categoryOf(request));
    expense.setSpentOn(spentOnOf(request));
    // Flush the orphan-removal deletes before re-inserting, or the new rows collide with the
    // old ones on the (expense_id, user_id) unique constraint within one flush.
    expense.clearParticipants();
    expenseRepository.flush();
    addShares(expense, split);
    expenseRepository.saveAndFlush(expense);
    log.info("Updated expense {} in group {} by user {}", expenseId, groupId, requesterId);
    return toResponse(expense);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteExpense(UUID requesterId, UUID groupId, UUID expenseId, Long expectedVersion) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    Expense expense =
        expenseRepository
            .findById(expenseId)
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    if (!expense.getGroup().getId().equals(groupId)) {
      throw new NotFoundException("Expense not found in this group");
    }
    com.spliteasy.config.LedgerContract.requireCurrency(expense.getCurrency());
    versionPolicy.requireExpected(expectedVersion, expense.getVersion());
    requireActiveExpenseParticipants(expense);
    expenseRepository.delete(expense); // cascade/orphanRemoval deletes expense_participants
    log.info("Deleted expense {} from group {} by user {}", expenseId, groupId, requesterId);
  }

  private void requireActiveExpenseParticipants(Expense expense) {
    Set<UUID> active =
        new HashSet<>(membershipRepository.findUserIdsByGroupId(expense.getGroup().getId()));
    if (!active.contains(expense.getPaidBy().getId())
        || expense.getParticipants().stream()
            .anyMatch(p -> !active.contains(p.getUser().getId()))) {
      throw new ConflictException("Re-add former participants before changing this expense.");
    }
  }

  /** Everything needed to write an expense's split — computed once, reused by create and update. */
  private record ComputedSplit(
      SplitType splitType,
      UUID payerId,
      List<Share> shares,
      Map<UUID, User> usersById,
      Map<UUID, Long> inputs) {}

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
      ctx =
          SplitContext.forEqual(
              request.resolvedAmountMinor(),
              payerId,
              resolveParticipants(request.participantUserIds(), memberIds));
    } else {
      ctx =
          SplitContext.forSplits(
              request.resolvedAmountMinor(), payerId, requireSplits(request.splits(), memberIds));
    }
    List<Share> shares = strategies.get(splitType).split(ctx);
    Map<UUID, User> usersById = loadUsers(shares.stream().map(Share::userId).toList(), payerId);
    Map<UUID, Long> inputs =
        splitType == SplitType.EQUAL
            ? shares.stream().collect(Collectors.toMap(Share::userId, share -> 1L))
            : ctx.splits().stream()
                .collect(Collectors.toMap(SplitInput::userId, SplitInput::value));
    return new ComputedSplit(splitType, payerId, shares, usersById, inputs);
  }

  private ExpenseCategory categoryOf(CreateExpenseRequest request) {
    return request.category() != null ? request.category() : ExpenseCategory.OTHER;
  }

  private LocalDate spentOnOf(CreateExpenseRequest request) {
    return request.spentOn() != null ? request.spentOn() : LocalDate.now();
  }

  private void addShares(Expense expense, ComputedSplit split) {
    for (Share share : split.shares()) {
      expense
          .addParticipant(split.usersById().get(share.userId()), share.shareCents())
          .setSplitValue(split.inputs().get(share.userId()));
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
        throw new BadRequestException(
            "Participant " + s.userId() + " is not a member of this group");
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
    Map<UUID, Long> myShare =
        participantRepository.findSharesByUserAndGroups(requesterId, List.of(groupId)).stream()
            .collect(
                Collectors.toMap(
                    ExpenseParticipantRepository.ExpenseShareView::getExpenseId,
                    ExpenseParticipantRepository.ExpenseShareView::getShareCents));
    return expenseRepository.findSummariesByGroupId(groupId).stream()
        .map(
            v ->
                new ExpenseSummary(
                    v.getId(),
                    v.getDescription(),
                    v.getAmountCents(),
                    new UserSummary(v.getPayerId(), v.getPayerEmail(), v.getPayerDisplayName()),
                    v.getParticipantCount(),
                    v.getCategory(),
                    v.getSpentOn(),
                    viewerDelta(
                        requesterId,
                        v.getPayerId(),
                        v.getAmountCents(),
                        myShare.getOrDefault(v.getId(), 0L)),
                    v.getCreatedAt(),
                    v.getVersion(),
                    v.getCurrency()))
        .toList();
  }

  /**
   * The requesting member's net for one expense: positive = they lent (paid more than their share),
   * negative = they borrowed (their share), 0 = not involved. Shared by the group expense list and
   * the dashboard activity feed so both read the same everywhere.
   */
  static long viewerDelta(UUID viewerId, UUID payerId, long amountCents, long viewerShareCents) {
    long paid = viewerId.equals(payerId) ? amountCents : 0L;
    return paid - viewerShareCents;
  }

  @Transactional(readOnly = true)
  public ExpenseResponse getExpense(UUID requesterId, UUID groupId, UUID expenseId) {
    membershipGuard.requireMember(groupId, requesterId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
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
    List<SplitShare> shares =
        expense.getParticipants().stream()
            .map(
                p ->
                    new SplitShare(
                        UserSummary.from(p.getUser()), p.getShareCents(), expense.getCurrency()))
            .sorted(Comparator.comparing(s -> s.user().displayName()))
            .toList();
    // Old writers cannot retain non-equal intent. Expose safe exact editing, never infer ratios.
    boolean equal = expense.getSplitType() == SplitType.EQUAL;
    boolean unknownIntent =
        expense.getParticipants().stream().anyMatch(p -> p.getSplitValue() == null);
    SplitType type = !equal && unknownIntent ? SplitType.UNEQUAL : expense.getSplitType();
    List<SplitInput> inputs =
        equal
            ? null
            : expense.getParticipants().stream()
                .map(
                    p ->
                        new SplitInput(
                            p.getUser().getId(),
                            unknownIntent ? p.getShareCents() : p.getSplitValue()))
                .sorted(Comparator.comparing(SplitInput::userId))
                .toList();
    return new ExpenseResponse(
        expense.getId(),
        expense.getGroup().getId(),
        expense.getDescription(),
        expense.getAmountCents(),
        type,
        expense.getCategory(),
        expense.getSpentOn(),
        UserSummary.from(expense.getPaidBy()),
        shares,
        equal
            ? expense.getParticipants().stream().map(p -> p.getUser().getId()).sorted().toList()
            : null,
        inputs,
        expense.getCreatedAt(),
        expense.getVersion(),
        expense.getCurrency());
  }
}
