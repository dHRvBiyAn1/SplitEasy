package com.spliteasy.settlement;

import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.entity.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CommitSettlementRequest(
    @NotNull LedgerCurrency currency,
    @NotEmpty List<@NotNull @Valid Allocation> allocations,
    @NotNull UUID operationId,
    LocalDate paidOn,
    PaymentMethod method,
    @Size(max = 280) String note) {}
