package com.spliteasy.settlement;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** One scope's payment: a group, or the pair's personal ledger when {@code groupName} is null. */
public record Allocation(
    @NotNull UUID scopeId,
    String groupName,
    @NotNull UUID payerId,
    @NotNull UUID payeeId,
    @Positive long amountMinor,
    UUID paymentId) {

  /** What a commit must match exactly: names and payment IDs are presentation/output only. */
  Allocation key() {
    return new Allocation(scopeId, null, payerId, payeeId, amountMinor, null);
  }
}
