package com.spliteasy.dto.payment;

import com.spliteasy.dto.common.UserSummary;

import com.spliteasy.entity.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UserSummary payer,
        UserSummary payee,
        long amountCents,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                UserSummary.from(payment.getPayer()),
                UserSummary.from(payment.getPayee()),
                payment.getAmountCents(),
                payment.getCreatedAt());
    }
}
