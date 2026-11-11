package com.spliteasy.service;

import com.spliteasy.config.LedgerContract;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.payment.LedgerRevision;
import com.spliteasy.dto.payment.RecordRefundRequest;
import com.spliteasy.dto.payment.RefundResponse;
import com.spliteasy.entity.Expense;
import com.spliteasy.entity.ExpenseRefund;
import com.spliteasy.entity.ExpenseRefundParticipant;
import com.spliteasy.entity.Participant;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ExpenseRefundRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.service.split.Share;
import com.spliteasy.service.split.SplitStrategy;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money returned out of an expense. A refund's economics are the reverse of that expense's own
 * allocation, so it takes no split input: the amount is distributed pro rata over the expense's
 * stored shares, with the receiving payer absorbing the remainder, and the allocation is stored so
 * a later expense correction cannot restate a refund that already happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

  private final ExpenseRefundRepository refundRepository;
  private final ExpenseRepository expenseRepository;
  private final UserRepository userRepository;
  private final GroupWriteGuard groupWriteGuard;
  private final MembershipGuard membershipGuard;
  private final FinancialParticipantService financialParticipants;
  private final CreateOperations createOperations;
  private final LedgerHistory history;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RefundResponse recordRefund(
      UUID requesterId, UUID groupId, UUID expenseId, RecordRefundRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Expense expense = activeExpense(groupId, expenseId);
    return createOperations.create(
        requesterId,
        "refund",
        expenseId,
        request.operationId(),
        request,
        RefundResponse.class,
        replayed -> {},
        () -> write(requesterId, expense, request));
  }

  private RefundResponse write(UUID requesterId, Expense expense, RecordRefundRequest request) {
    if (request.currency() != expense.getCurrency()) {
      throw new BadRequestException(
          "A refund is recorded in the expense's own currency (" + expense.getCurrency() + ").");
    }
    long remaining =
        expense.getAmountCents() - refundRepository.sumActiveByExpense(expense.getId());
    if (request.amountMinor() > remaining) {
      throw new BadRequestException(
          "This expense has only " + remaining + " left to refund in minor units.");
    }
    requireActiveParticipants(expense);

    ExpenseRefund refund =
        new ExpenseRefund(
            expense,
            expense.getPaidBy(),
            request.amountMinor(),
            request.refundedOn(),
            request.note(),
            requesterId);
    refund.setRecorder(userRepository.getReferenceById(requesterId));
    Map<UUID, Participant> people = participantsOf(expense);
    for (Share share : allocate(expense, request.amountMinor())) {
      refund
          .getParticipants()
          .add(
              new ExpenseRefundParticipant(refund, people.get(share.userId()), share.shareCents()));
    }
    // Flush before the revision insert: the JDBC history write needs the refund row to exist.
    refundRepository.saveAndFlush(refund);
    RefundResponse response = RefundResponse.from(refund);
    history.record(
        LedgerHistory.Subject.REFUND,
        refund.getId(),
        refund.getVersion(),
        "CREATED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        request.operationId(),
        null,
        response);
    log.info(
        "Recorded refund {} of {} on expense {} by account {}",
        refund.getId(),
        request.amountMinor(),
        expense.getId(),
        requesterId);
    return response;
  }

  /**
   * Pro rata by the expense's stored shares. {@code BigInteger} because amount × share exceeds a
   * long at the catalogue's bounds; the receiving payer absorbs the leftover minor units under the
   * same rule every split strategy uses, so the allocation sums exactly to the refund.
   */
  private List<Share> allocate(Expense expense, long amount) {
    BigInteger total = BigInteger.valueOf(expense.getAmountCents());
    BigInteger refunded = BigInteger.valueOf(amount);
    List<UUID> sortedIds =
        expense.getParticipants().stream()
            .map(p -> p.getUser().getId())
            .sorted(Comparator.naturalOrder())
            .toList();
    Map<UUID, Long> shares = new HashMap<>();
    long allocated = 0;
    for (var participant : expense.getParticipants()) {
      long portion =
          refunded
              .multiply(BigInteger.valueOf(participant.getShareCents()))
              .divide(total)
              .longValueExact();
      shares.put(participant.getUser().getId(), portion);
      allocated += portion;
    }
    return SplitStrategy.absorbRemainder(
        shares, sortedIds, expense.getPaidBy().getId(), amount - allocated);
  }

  private Map<UUID, Participant> participantsOf(Expense expense) {
    Map<UUID, Participant> byId = new LinkedHashMap<>();
    for (var participant : expense.getParticipants()) {
      byId.put(participant.getUser().getId(), participant.getUser());
    }
    byId.put(expense.getPaidBy().getId(), expense.getPaidBy());
    return byId;
  }

  /** Same historical-participant guard an expense edit applies: no silent rewrite of history. */
  private void requireActiveParticipants(Expense expense) {
    Set<UUID> active = financialParticipants.active(expense.getGroup().getId());
    if (!active.contains(expense.getPaidBy().getId())
        || expense.getParticipants().stream()
            .anyMatch(p -> !active.contains(p.getUser().getId()))) {
      throw new ConflictException("Re-add former participants before refunding this expense.");
    }
  }

  /** 403 before 404: the caller's guard checks membership before this load. */
  private Expense activeExpense(UUID groupId, UUID expenseId) {
    Expense expense =
        expenseRepository
            .findByIdFetchAll(expenseId)
            .filter(e -> e.getGroup().getId().equals(groupId) && e.getDeletedAt() == null)
            .orElseThrow(() -> new NotFoundException("Expense not found in this group"));
    LedgerContract.requireCurrency(expense.getCurrency());
    return expense;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<RefundResponse> listRefunds(UUID requesterId, UUID groupId, UUID expenseId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    activeExpense(groupId, expenseId);
    return refundRepository.findActiveByExpenseId(expenseId).stream()
        .map(RefundResponse::from)
        .toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<LedgerRevision<RefundResponse>> getHistory(
      UUID requesterId, UUID groupId, UUID expenseId, UUID refundId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    refund(groupId, expenseId, refundId);
    return history.list(LedgerHistory.Subject.REFUND, refundId, RefundResponse.class);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deleteRefund(
      UUID requesterId, UUID groupId, UUID expenseId, UUID refundId, Long expectedVersion) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    ExpenseRefund refund = refund(groupId, expenseId, refundId);
    if (refund.getDeletedAt() != null) {
      throw new NotFoundException("Refund not found on this expense");
    }
    if (expectedVersion == null) {
      throw new BadRequestException(
          "expectedVersion is required. Reload the refund before deleting it.");
    }
    if (expectedVersion != refund.getVersion()) {
      throw new ConflictException(
          "This refund changed. Reload and review the latest changes before deleting it again.");
    }
    RefundResponse before = RefundResponse.from(refund);
    refund.setDeletedAt(java.time.Instant.now());
    refundRepository.saveAndFlush(refund);
    history.record(
        LedgerHistory.Subject.REFUND,
        refundId,
        refund.getVersion(),
        "DELETED",
        UserSummary.from(userRepository.getReferenceById(requesterId)),
        null,
        before,
        RefundResponse.from(refund));
    log.info("Deleted refund {} on expense {} by account {}", refundId, expenseId, requesterId);
  }

  private ExpenseRefund refund(UUID groupId, UUID expenseId, UUID refundId) {
    ExpenseRefund refund =
        refundRepository
            .findByIdFetchAll(refundId)
            .orElseThrow(() -> new NotFoundException("Refund not found"));
    if (!refund.getGroup().getId().equals(groupId)
        || !refund.getExpense().getId().equals(expenseId)) {
      throw new NotFoundException("Refund not found on this expense");
    }
    LedgerContract.requireCurrency(refund.getCurrency());
    return refund;
  }
}
