package com.spliteasy.service;

import com.spliteasy.dto.PaymentResponse;
import com.spliteasy.dto.RecordPaymentRequest;
import com.spliteasy.entity.Group;
import com.spliteasy.entity.Payment;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.repository.UserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final GroupRepository groupRepository;
    private final GroupMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final MembershipGuard membershipGuard;

    public PaymentService(
            PaymentRepository paymentRepository,
            GroupRepository groupRepository,
            GroupMembershipRepository membershipRepository,
            UserRepository userRepository,
            MembershipGuard membershipGuard) {
        this.paymentRepository = paymentRepository;
        this.groupRepository = groupRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.membershipGuard = membershipGuard;
    }

    @Transactional
    public PaymentResponse recordPayment(UUID requesterId, UUID groupId, RecordPaymentRequest request) {
        membershipGuard.requireMember(groupId, requesterId);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new NotFoundException("Group not found"));

        UUID payerId = request.payerUserId();
        UUID payeeId = request.payeeUserId();
        if (payerId.equals(payeeId)) {
            throw new BadRequestException("A payment must be between two different people");
        }
        // Overpayment is intentionally allowed (people prepay/overpay/round). We only require
        // that both parties are members — not that the amount matches any outstanding debt.
        // One membership query + one batched user fetch (mirrors ExpenseService.loadUsers).
        Set<UUID> memberIds = new HashSet<>(membershipRepository.findUserIdsByGroupId(groupId));
        if (!memberIds.contains(payerId)) {
            throw new BadRequestException("The payer must be a member of this group");
        }
        if (!memberIds.contains(payeeId)) {
            throw new BadRequestException("The payee must be a member of this group");
        }

        Map<UUID, User> users = userRepository.findAllById(List.of(payerId, payeeId)).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Payment payment = paymentRepository.save(
                new Payment(group, users.get(payerId), users.get(payeeId), request.amountCents()));
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> listPayments(UUID requesterId, UUID groupId) {
        membershipGuard.requireMember(groupId, requesterId);
        return paymentRepository.findByGroupIdFetchUsers(groupId).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
