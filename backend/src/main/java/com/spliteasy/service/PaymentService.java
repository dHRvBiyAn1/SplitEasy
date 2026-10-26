package com.spliteasy.service;

import com.spliteasy.dto.payment.PaymentResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.Participant;
import com.spliteasy.entity.Payment;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.repository.ParticipantRepository;
import com.spliteasy.repository.PaymentRepository;
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
    if (request.operationId() == null) {
      payment = paymentRepository.save(payment);
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
              request.operationId());
      // READ COMMITTED gives this lookup a fresh snapshot after ON CONFLICT waits.
      payment =
          paymentRepository
              .findByGroupIdAndRecordedByAndOperationId(groupId, requesterId, request.operationId())
              .orElseThrow();
      if (inserted == 0) return replay(payment, request);
    }
    log.info(
        "Recorded payment {} in group {} by account {}", payment.getId(), groupId, requesterId);
    return PaymentResponse.from(payment);
  }

  private PaymentResponse replay(Payment payment, RecordPaymentRequest request) {
    if (!payment.getPayer().getId().equals(request.payerUserId())
        || !payment.getPayee().getId().equals(request.payeeUserId())
        || payment.getAmountCents() != request.resolvedAmountMinor()
        || payment.getCurrency() != request.resolvedCurrency()) {
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
