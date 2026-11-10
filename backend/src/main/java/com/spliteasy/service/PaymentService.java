package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.payment.CorrectPaymentRequest;
import com.spliteasy.dto.payment.DeletedPayment;
import com.spliteasy.dto.payment.LedgerRevision;
import com.spliteasy.dto.payment.PaymentResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.dto.payment.RestorePaymentRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.Participant;
import com.spliteasy.entity.Payment;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.ParticipantRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

  private final PaymentRepository paymentRepository;
  private final tools.jackson.databind.ObjectMapper mapper;
  private final GroupWriteGuard groupWriteGuard;
  private final FinancialParticipantService financialParticipants;
  private final ParticipantRepository participantIdentities;
  private final MembershipGuard membershipGuard;
  private final LedgerHistory history;
  private final UserRepository userRepository;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentResponse recordPayment(
      UUID requesterId, UUID groupId, RecordPaymentRequest request) {
    Group group = groupWriteGuard.lockForMember(groupId, requesterId);
    com.spliteasy.config.LedgerContract.requireCurrency(request.resolvedCurrency());
    if (request.operationId() != null) {
      var existing =
          paymentRepository.findByGroupIdAndRecordedByAndOperationId(
              groupId, requesterId, request.operationId());
      if (existing.isPresent()) return replay(existing.get(), request);
    }
    financialParticipants.requireCompatible(groupId);
    UUID payerId = request.payerUserId();
    UUID payeeId = request.payeeUserId();
    if (financialParticipants
        .canonical(groupId, payerId)
        .equals(financialParticipants.canonical(groupId, payeeId))) {
      throw new BadRequestException("A payment must be between two different people");
    }
    // Overpayment is intentionally allowed (people prepay/overpay/round). We only require
    // that both parties are active financial participants — not that the amount matches any
    // outstanding debt.
    // One roster query + one batched participant fetch.
    Set<UUID> activeParticipantIds = financialParticipants.active(groupId);
    if (!activeParticipantIds.contains(payerId)) {
      throw new BadRequestException("The payer must be an active participant in this group");
    }
    if (!activeParticipantIds.contains(payeeId)) {
      throw new BadRequestException("The payee must be an active participant in this group");
    }

    Map<UUID, Participant> participantsById =
        participantIdentities.findAllById(List.of(payerId, payeeId)).stream()
            .collect(Collectors.toMap(Participant::getId, Function.identity()));
    Payment payment =
        new Payment(
            group,
            participantsById.get(payerId),
            participantsById.get(payeeId),
            request.resolvedAmountMinor());
    payment.setCurrency(request.resolvedCurrency());
    if (request.paidOn() != null) payment.setPaidOn(request.paidOn());
    payment.setMethod(request.resolvedMethod());
    payment.setNote(request.note());
    // The recorder is the authenticated requester, independent of who paid (B01).
    payment.setRecordedBy(requesterId);
    // recorder mirrors recorded_by read-only, so setting it only names the actor in the response.
    payment.setRecorder(userRepository.getReferenceById(requesterId));
    if (request.operationId() == null) {
      // Flush before the revision insert: the JDBC history write needs the payment row to exist.
      payment = paymentRepository.saveAndFlush(payment);
    } else {
      int inserted =
          paymentRepository.insertOperation(
              UUID.randomUUID(),
              groupId,
              payerId,
              payeeId,
              request.resolvedAmountMinor(),
              request.resolvedCurrency().name(),
              payment.getCreatedAt(),
              requesterId,
              request.operationId(),
              payment.getPaidOn(),
              payment.getMethod().name(),
              payment.getNote());
      // READ COMMITTED gives this lookup a fresh snapshot after ON CONFLICT waits.
      payment =
          paymentRepository
              .findByGroupIdAndRecordedByAndOperationId(groupId, requesterId, request.operationId())
              .orElseThrow();
      if (inserted == 0) return replay(payment, request);
    }
    log.info(
        "Recorded payment {} in group {} by account {}", payment.getId(), groupId, requesterId);
    PaymentResponse created = PaymentResponse.from(payment);
    history.record(
        LedgerHistory.Subject.PAYMENT,
        payment.getId(),
        payment.getVersion(),
        "CREATED",
        actor(requesterId),
        request.operationId(),
        null,
        created);
    return created;
  }

  private UserSummary actor(UUID requesterId) {
    return UserSummary.from(userRepository.getReferenceById(requesterId));
  }

  /**
   * Payments have never carried a version, so there is no legacy writer to grandfather: a
   * correction, delete or restore always states the version it saw.
   */
  private void requireExpected(long expected, long actual) {
    if (expected != actual) {
      throw new ConflictException(
          "This payment changed. Reload and review the latest changes before saving or deleting again.");
    }
  }

  /** 403 before 404: membership is checked by the caller's guard before this load. */
  private Payment activePayment(UUID groupId, UUID paymentId) {
    Payment payment = anyPayment(groupId, paymentId);
    if (payment.getDeletedAt() != null) {
      throw new NotFoundException("Payment not found in this group");
    }
    return payment;
  }

  private Payment anyPayment(UUID groupId, UUID paymentId) {
    Payment payment =
        paymentRepository
            .findByIdFetchAll(paymentId)
            .orElseThrow(() -> new NotFoundException("Payment not found"));
    if (!payment.getGroup().getId().equals(groupId)) {
      throw new NotFoundException("Payment not found in this group");
    }
    com.spliteasy.config.LedgerContract.requireCurrency(payment.getCurrency());
    return payment;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PaymentResponse getPayment(UUID requesterId, UUID groupId, UUID paymentId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return PaymentResponse.from(activePayment(groupId, paymentId));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<LedgerRevision<PaymentResponse>> getHistory(
      UUID requesterId, UUID groupId, UUID paymentId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    anyPayment(groupId, paymentId);
    return history.list(LedgerHistory.Subject.PAYMENT, paymentId, PaymentResponse.class);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DeletedPayment> listDeleted(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return paymentRepository.findDeletedByGroupId(groupId).stream()
        .map(p -> new DeletedPayment(PaymentResponse.from(p), p.getDeletedAt()))
        .toList();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentResponse correctPayment(
      UUID requesterId, UUID groupId, UUID paymentId, CorrectPaymentRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Payment payment = activePayment(groupId, paymentId);
    requireExpected(request.expectedVersion(), payment.getVersion());
    if (request.currency() != null && request.currency() != payment.getCurrency()) {
      throw new ConflictException(
          "A recorded payment keeps its original currency. Record a new payment instead.");
    }
    var parties =
        requireDistinctActiveParties(groupId, request.payerUserId(), request.payeeUserId());
    PaymentResponse before = PaymentResponse.from(payment);
    payment.setPayer(parties.get(request.payerUserId()));
    payment.setPayee(parties.get(request.payeeUserId()));
    payment.setAmountCents(request.amountMinor());
    payment.setPaidOn(request.paidOn());
    payment.setMethod(request.resolvedMethod());
    payment.setNote(request.note());
    paymentRepository.saveAndFlush(payment);
    PaymentResponse after = PaymentResponse.from(payment);
    history.record(
        LedgerHistory.Subject.PAYMENT,
        paymentId,
        payment.getVersion(),
        "CORRECTED",
        actor(requesterId),
        request.operationId(),
        before,
        after);
    log.info("Corrected payment {} in group {} by account {}", paymentId, groupId, requesterId);
    return after;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void deletePayment(
      UUID requesterId, UUID groupId, UUID paymentId, Long expectedVersion, UUID operationId) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Payment payment = activePayment(groupId, paymentId);
    if (expectedVersion == null) {
      throw new BadRequestException(
          "expectedVersion is required. Reload the payment before deleting it.");
    }
    requireExpected(expectedVersion, payment.getVersion());
    PaymentResponse before = PaymentResponse.from(payment);
    payment.setDeletedAt(java.time.Instant.now());
    paymentRepository.saveAndFlush(payment);
    history.record(
        LedgerHistory.Subject.PAYMENT,
        paymentId,
        payment.getVersion(),
        "DELETED",
        actor(requesterId),
        operationId,
        before,
        PaymentResponse.from(payment));
    log.info("Deleted payment {} from group {} by account {}", paymentId, groupId, requesterId);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentResponse restorePayment(
      UUID requesterId, UUID groupId, UUID paymentId, RestorePaymentRequest request) {
    groupWriteGuard.lockForMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    Payment payment = anyPayment(groupId, paymentId);
    requireExpected(request.expectedVersion(), payment.getVersion());
    if (payment.getDeletedAt() == null) {
      throw new ConflictException(
          "This payment is already active. Reload and review the latest changes.");
    }
    requireDistinctActiveParties(groupId, payment.getPayer().getId(), payment.getPayee().getId());
    PaymentResponse before = PaymentResponse.from(payment);
    payment.setDeletedAt(null);
    paymentRepository.saveAndFlush(payment);
    PaymentResponse after = PaymentResponse.from(payment);
    history.record(
        LedgerHistory.Subject.PAYMENT,
        paymentId,
        payment.getVersion(),
        "RESTORED",
        actor(requesterId),
        request.operationId(),
        before,
        after);
    log.info("Restored payment {} in group {} by account {}", paymentId, groupId, requesterId);
    return after;
  }

  /** Both sides of a settlement must be distinct, currently active financial participants. */
  private Map<UUID, Participant> requireDistinctActiveParties(
      UUID groupId, UUID payerId, UUID payeeId) {
    if (financialParticipants
        .canonical(groupId, payerId)
        .equals(financialParticipants.canonical(groupId, payeeId))) {
      throw new BadRequestException("A payment must be between two different people");
    }
    Set<UUID> active = financialParticipants.active(groupId);
    if (!active.contains(payerId) || !active.contains(payeeId)) {
      throw new ConflictException("Re-add former participants before changing this payment.");
    }
    return participantIdentities.findAllById(List.of(payerId, payeeId)).stream()
        .collect(Collectors.toMap(Participant::getId, Function.identity()));
  }

  private PaymentResponse replay(Payment payment, RecordPaymentRequest request) {
    if (!payment.getPayer().getId().equals(request.payerUserId())
        || !payment.getPayee().getId().equals(request.payeeUserId())
        || payment.getAmountCents() != request.resolvedAmountMinor()
        || payment.getCurrency() != request.resolvedCurrency()
        || (request.paidOn() != null && !request.paidOn().equals(payment.getPaidOn()))
        || payment.getMethod() != request.resolvedMethod()
        || !java.util.Objects.equals(payment.getNote(), request.note())) {
      throw new ConflictException("Operation ID already used for a different payment");
    }
    var response = PaymentResponse.from(payment);
    com.spliteasy.config.ParticipantContract.requireReceipt(mapper.valueToTree(response));
    return response;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PaymentResponse> listPayments(UUID requesterId, UUID groupId) {
    membershipGuard.requireMember(groupId, requesterId);
    financialParticipants.requireCompatible(groupId);
    return paymentRepository.findByGroupIdFetchUsers(groupId).stream()
        .map(PaymentResponse::from)
        .toList();
  }
}
