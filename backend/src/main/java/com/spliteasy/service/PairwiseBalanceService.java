package com.spliteasy.service;

import com.spliteasy.dto.balance.DirectObligation;
import com.spliteasy.dto.balance.PersonBalance;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The viewer's counterparties in the group's current obligation mode. Positive = owed to viewer.
 */
@Service
@RequiredArgsConstructor
public class PairwiseBalanceService {
  private final ObligationProjectionService projection;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PersonBalance> forGroup(UUID requesterId, UUID groupId) {
    return forViewer(requesterId, projection.forGroup(requesterId, groupId));
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DirectObligation> obligations(UUID requesterId, UUID groupId) {
    return projection.rawForGroup(requesterId, groupId);
  }

  /** Already-authorized dashboard snapshot; guarded writes also hold the group row lock. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<PersonBalance> compute(UUID requesterId, UUID groupId) {
    return forViewer(requesterId, projection.snapshot(requesterId, groupId));
  }

  private List<PersonBalance> forViewer(UUID viewer, List<DirectObligation> obligations) {
    return obligations.stream()
        .filter(d -> d.from().id().equals(viewer) || d.to().id().equals(viewer))
        .map(
            d ->
                d.from().id().equals(viewer)
                    ? new PersonBalance(d.to(), -d.amountMinor(), d.currency())
                    : new PersonBalance(d.from(), d.amountMinor(), d.currency()))
        .sorted(
            Comparator.comparingLong((PersonBalance b) -> Math.abs(b.netCents()))
                .reversed()
                .thenComparing(b -> b.user().displayName())
                .thenComparing(b -> b.user().id()))
        .toList();
  }
}
