package com.spliteasy.service;

import com.spliteasy.dto.PaymentResponse;
import com.spliteasy.dto.RecordPaymentRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.Payment;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;

    public PaymentService(
            PaymentRepository paymentRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            UserRepository userRepository) {
        this.paymentRepository = paymentRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public PaymentResponse recordPayment(UUID requesterId, UUID groupId, RecordPaymentRequest request) {
        requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

        UUID payerId = request.payerUserId();
        UUID payeeId = request.payeeUserId();
        if (payerId.equals(payeeId)) {
            throw new BadRequestException("A payment must be between two different people");
        }
        // Overpayment is intentionally allowed (people prepay/overpay/round). We only require
        // that both parties are members — not that the amount matches any outstanding debt.
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, payerId)) {
            throw new BadRequestException("The payer must be a member of this group");
        }
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, payeeId)) {
            throw new BadRequestException("The payee must be a member of this group");
        }

        User payer = userRepository.findById(payerId).orElseThrow(() -> new NotFoundException("Payer not found"));
        User payee = userRepository.findById(payeeId).orElseThrow(() -> new NotFoundException("Payee not found"));
        Payment payment = paymentRepository.save(new Payment(group, payer, payee, request.amountCents()));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(UUID requesterId, UUID groupId) {
        requireMember(groupId, requesterId);
        return paymentRepository.findByGroupIdFetchUsers(groupId).stream()
                .map(PaymentResponse::from)
                .toList();
    }

    private void requireMember(UUID groupId, UUID userId) {
        if (!membershipRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ForbiddenException("You are not a member of this group");
        }
    }
}
