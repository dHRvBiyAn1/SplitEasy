package com.spliteasy.dto.payment;

import java.time.Instant;

public record DeletedPayment(PaymentResponse payment, Instant deletedAt) {}
