package com.spliteasy.service;

import com.spliteasy.config.LedgerContract;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.DeletedExpense;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseRevision;
import com.spliteasy.dto.expense.ExpenseSummary;
import com.spliteasy.dto.expense.RestoreExpenseRequest;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.expense.SplitShare;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.Participant;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.ParticipantRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.service.split.Share;
import com.spliteasy.service.split.SplitContext;
import com.spliteasy.service.split.SplitStrategy;
import java.time.Instant;
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

  private final FinancialParticipantService financialParticipants;
  private final ParticipantRepository participantIdentities;
  private final ExpenseHistory history;
  private final CreateOperations createOperations;
  private final ExpenseVersionPolicy versionPolicy;
  private final jakarta.persistence.EntityManager entityManager;
  private final ExpenseRepository expenseRepository;
  private final ExpenseParticipantRepository participantRepository;
  private final GroupWriteGuard groupWriteGuard;
  private final UserRepository userRepository;
  private final MembershipGuard membershipGuard;

  /** Split-type → strategy, built from the strategy beans. No if/else on split type here. */
  private final Map<SplitType, SplitStrategy> strategies;

  public ExpenseService(
      FinancialParticipantService financialParticipants,
      ParticipantRepository participantIdentities,
      ExpenseHistory history,
      CreateOperations createOperations,
      ExpenseVersionPolicy versionPolicy,
      jakarta.persistence.EntityManager entityManager,
      ExpenseRepository expenseRepository,
      ExpenseParticipantRepository participantRepository,
      GroupWriteGuard groupWriteGuard,
      UserRepository userRepository,
      MembershipGuard membershipGuard,
      List<SplitStrategy> splitStrategies) {
    this.financialParticipants = financialParticipants;
    this.participantIdentities = participantIdentities;
    this.history = history;
    this.createOperations = createOperations;
    this.versionPolicy = versionPolicy;
    this.entityManager = entityManager;
    this.expenseRepository = expenseRepository;
    this.participantRepository = participantRepository;
    this.groupWriteGuard = groupWriteGuard;
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
    LedgerContract.requireCurrency(request.resolvedCurrency());
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
    financialParticipants.requireCompatible(groupId);
    log.debug("Creating expense in group {} by user {}", groupId, requesterId);

    ComputedSplit split = computeSplit(groupId, request);
    Expense expense =
        new Expense(
            group,
            request.description().trim(),
            request.resolvedAmountMinor(),
            split.participantsById().get(split.payerId()),
            split.splitType(),
            categoryOf(request),
            spentOnOf(request));
    expense.setCurrency(request.resolvedCurrency());
    addShares(expense, split);
    expenseRepository.saveAndFlush(expense);
    log.info("Created expense {} in group {} by account {}", expense.getId(), groupId, requesterId);
    ExpenseResponse response = toResponse(expense);
    history.record(
        "CREATED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        request.operationId(),
        null,
        response);
    return response;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse updateExpense(
      UUID requesterId, UUID groupId, UUID expenseId, CreateExpenseRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    if (!expense.getGroup().getId().equals(groupId) || expense.getDeletedAt() != null) {
      throw new NotFoundException("Expense not found in this group");
    }

    LedgerContract.requireCurrency(expense.getCurrency());
    if (expense.getCurrency() != request.resolvedCurrency()) {
      throw new BadRequestException(
          "Expense currency cannot change. Create a separate expense in the correct currency.");
    }
    versionPolicy.requireExpected(request.expectedVersion(), expense.getVersion());
    requireActiveExpenseParticipants(expense);

    ExpenseResponse before = toResponse(expense);
    // Validate before advancing; parent versions must also cover child-only split intent changes.
    ComputedSplit split =
        computeSplit(
            groupId,
            request,
            expense.getParticipants().stream()
                .map(p -> p.getUser().getId())
                .collect(Collectors.toSet()));
    // Immediate increment (unlike deferred OPTIMISTIC_FORCE_INCREMENT) keeps the response current.
    entityManager.lock(expense, jakarta.persistence.LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    expense.setDescription(request.description().trim());
    expense.setAmountCents(request.resolvedAmountMinor());
    expense.setPaidBy(split.participantsById().get(split.payerId()));
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
    ExpenseResponse response = toResponse(expense);
    history.record(
        "REPLACED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        request.operationId(),
        before,
        response);
    return response;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteExpense(UUID requesterId, UUID groupId, UUID expenseId, Long expectedVersion) {
    deleteExpense(requesterId, groupId, expenseId, expectedVersion, null);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteExpense(
      UUID requesterId, UUID groupId, UUID expenseId, Long expectedVersion, UUID operationId) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    if (!expense.getGroup().getId().equals(groupId) || expense.getDeletedAt() != null) {
      throw new NotFoundException("Expense not found in this group");
    }
    LedgerContract.requireCurrency(expense.getCurrency());
    versionPolicy.requireExpected(expectedVersion, expense.getVersion());
    requireActiveExpenseParticipants(expense);
    ExpenseResponse before = toResponse(expense);
    expense.setDeletedAt(Instant.now());
    expenseRepository.saveAndFlush(expense);
    history.record(
        "DELETED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        operationId,
        before,
        toResponse(expense));
    log.info("Deleted expense {} from group {} by user {}", expenseId, groupId, requesterId);
  }

  private void requireActiveExpenseParticipants(Expense expense) {
    Set<UUID> active = financialParticipants.active(expense.getGroup().getId());
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
      Map<UUID, Participant> participantsById,
      Map<UUID, Long> inputs) {}

  /**
   * Validates payer/participants (financial eligibility) and computes shares via the split
   * strategy.
   */
  private ComputedSplit computeSplit(UUID groupId, CreateExpenseRequest request) {
    return computeSplit(groupId, request, Set.of());
  }

  private ComputedSplit computeSplit(
      UUID groupId, CreateExpenseRequest request, Set<UUID> historicalIds) {
    Set<UUID> activeParticipantIds = financialParticipants.active(groupId);
    UUID payerId = request.paidByUserId();
    if (!activeParticipantIds.contains(payerId)) {
      throw new BadRequestException("The payer must be an active participant in this group");
    }
    SplitType splitType = Objects.requireNonNullElse(request.splitType(), SplitType.EQUAL);
    // Service resolves financial eligibility; the strategy owns the split math + its validation.
    SplitContext ctx;
    if (splitType == SplitType.EQUAL) {
      ctx =
          SplitContext.forEqual(
              request.resolvedAmountMinor(),
              payerId,
              resolveParticipants(
                  request.participantUserIds() == null || request.participantUserIds().isEmpty()
                      ? financialParticipants.roster(groupId).stream()
                          .map(ParticipantSummary::id)
                          .toList()
                      : request.participantUserIds(),
                  activeParticipantIds));
    } else {
      ctx =
          SplitContext.forSplits(
              request.resolvedAmountMinor(),
              payerId,
              requireSplits(request.splits(), activeParticipantIds));
    }
    List<Share> shares = strategies.get(splitType).split(ctx);
    var originals = financialParticipants.originals(groupId);
    var owners =
        shares.stream()
            .collect(
                Collectors.groupingBy(
                    share -> originals.get(share.userId()).canonicalParticipantId()));
    for (var owned : owners.values())
      if (owned.size() > 1
          && !historicalIds.containsAll(owned.stream().map(Share::userId).toList()))
        throw new ConflictException(
            "These participants now belong to the same person. Reload and review the split without duplicate identities.");
    Map<UUID, Participant> participantsById =
        loadParticipants(shares.stream().map(Share::userId).toList(), payerId);
    Map<UUID, Long> inputs =
        splitType == SplitType.EQUAL
            ? shares.stream().collect(Collectors.toMap(Share::userId, share -> 1L))
            : ctx.splits().stream()
                .collect(Collectors.toMap(SplitInput::userId, SplitInput::value));
    return new ComputedSplit(splitType, payerId, shares, participantsById, inputs);
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
          .addParticipant(split.participantsById().get(share.userId()), share.shareCents())
          .setSplitValue(split.inputs().get(share.userId()));
    }
  }

  /** A non-empty, duplicate-free split list where every financial participant is active. */
  private List<SplitInput> requireSplits(List<SplitInput> splits, Set<UUID> activeParticipantIds) {
    if (splits == null || splits.isEmpty()) {
      throw new BadRequestException("This split type requires a per-participant split list");
    }
    Set<UUID> seen = new HashSet<>();
    for (SplitInput s : splits) {
      if (!activeParticipantIds.contains(s.userId())) {
        throw new BadRequestException(
            "Participant " + s.userId() + " is not an active participant in this group");
      }
      if (!seen.add(s.userId())) {
        throw new BadRequestException("Duplicate participant in split: " + s.userId());
      }
    }
    return splits;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<ExpenseSummary> listExpenses(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    // The requester's own share of each expense in the group — drives the "you lent / you
    // borrowed" delta without an N+1 (one query for the whole group).
    Map<UUID, Long> myShare =
        participantRepository.findSharesByUserAndGroups(requesterId, List.of(groupId)).stream()
            .collect(
                Collectors.toMap(
                    ExpenseParticipantRepository.ExpenseShareView::getExpenseId,
                    ExpenseParticipantRepository.ExpenseShareView::getShareCents));
    var rows = expenseRepository.findSummariesByGroupId(groupId);
    var payers =
        participantIdentities
            .findAllById(
                rows.stream()
                    .map(ExpenseRepository.ExpenseSummaryView::getPayerId)
                    .distinct()
                    .toList())
            .stream()
            .map(ParticipantSummary::from)
            .collect(Collectors.toMap(ParticipantSummary::id, p -> p));
    return rows.stream()
        .map(
            v ->
                new ExpenseSummary(
                    v.getId(),
                    v.getDescription(),
                    v.getAmountCents(),
                    payers.get(v.getPayerId()),
                    v.getParticipantCount(),
                    v.getCategory(),
                    v.getSpentOn(),
                    viewerDelta(
                        requesterId,
                        payers.get(v.getPayerId()).canonicalParticipantId(),
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

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ExpenseResponse getExpense(UUID requesterId, UUID groupId, UUID expenseId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    if (!expense.getGroup().getId().equals(groupId) || expense.getDeletedAt() != null) {
      throw new NotFoundException("Expense not found in this group");
    }
    return toResponse(expense);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<ExpenseRevision> getHistory(UUID requesterId, UUID groupId, UUID expenseId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense =
        expenseRepository
            .findById(expenseId)
            .filter(e -> e.getGroup().getId().equals(groupId))
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    LedgerContract.requireCurrency(expense.getCurrency());
    return history.list(expenseId);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DeletedExpense> listDeleted(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return expenseRepository.findDeletedByGroupId(groupId).stream()
        .map(
            expense -> {
              LedgerContract.requireCurrency(expense.getCurrency());
              return new DeletedExpense(toResponse(expense), expense.getDeletedAt());
            })
        .toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ExpenseResponse restoreExpense(
      UUID requesterId, UUID groupId, UUID expenseId, RestoreExpenseRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .filter(e -> e.getGroup().getId().equals(groupId))
            .orElseThrow(() -> new NotFoundException("Expense not found"));
    LedgerContract.requireCurrency(expense.getCurrency());
    if (request.expectedVersion() == null) {
      throw new BadRequestException(
          "expectedVersion is required. Reload and review deleted expenses before restoring.");
    }
    versionPolicy.requireExpected(request.expectedVersion(), expense.getVersion());
    if (expense.getDeletedAt() == null) {
      throw new ConflictException(
          "This expense is already active. Reload and review the latest changes.");
    }
    requireActiveExpenseParticipants(expense);
    ExpenseResponse before = toResponse(expense);
    expense.setDeletedAt(null);
    expenseRepository.saveAndFlush(expense);
    ExpenseResponse response = toResponse(expense);
    history.record(
        "RESTORED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        request.operationId(),
        before,
        response);
    return response;
  }

  private List<UUID> resolveParticipants(List<UUID> requested, Set<UUID> activeParticipantIds) {
    if (requested == null || requested.isEmpty()) {
      // Default: each canonical active person shares the expense.
      return List.copyOf(activeParticipantIds);
    }
    List<UUID> distinct = requested.stream().distinct().toList();
    for (UUID id : distinct) {
      if (!activeParticipantIds.contains(id)) {
        throw new BadRequestException(
            "Participant " + id + " is not an active participant in this group");
      }
    }
    return distinct;
  }

  private Map<UUID, Participant> loadParticipants(List<UUID> participantIds, UUID payerId) {
    Set<UUID> ids = new LinkedHashSet<>(participantIds);
    ids.add(payerId);
    return participantIdentities.findAllById(ids).stream()
        .collect(Collectors.toMap(Participant::getId, Function.identity()));
  }

  private ExpenseResponse toResponse(Expense expense) {
    List<SplitShare> shares =
        expense.getParticipants().stream()
            .map(
                p ->
                    new SplitShare(
                        ParticipantSummary.from(p.getUser()),
                        p.getShareCents(),
                        expense.getCurrency()))
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
        ParticipantSummary.from(expense.getPaidBy()),
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
