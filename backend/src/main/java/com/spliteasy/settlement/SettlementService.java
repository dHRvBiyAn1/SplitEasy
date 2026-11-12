package com.spliteasy.settlement;

import com.spliteasy.config.ParticipantContract;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.repository.FriendRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.PersonalLedgerRepository;
import com.spliteasy.service.GroupWriteGuard;
import com.spliteasy.service.PairwiseBalanceService;
import com.spliteasy.service.PaymentService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Settles everything two friends owe each other in one currency, one ordinary payment per shared
 * scope (group or personal ledger), in a single transaction. Scopes are locked in ascending UUID
 * order so concurrent batches over the same groups cannot deadlock.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {
  private final FriendRepository friends;
  private final GroupMembershipRepository memberships;
  private final PersonalLedgerRepository personalLedgers;
  private final PairwiseBalanceService pairwise;
  private final GroupWriteGuard writes;
  private final PaymentService payments;
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  /** Scopes the pair shares plus the allocations computed from their current obligations. */
  private record Computed(List<UUID> scopes, SettlementPreview preview) {}

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public SettlementPreview preview(UUID owner, UUID friend, LedgerCurrency currency) {
    authorize(owner, friend);
    return compute(owner, friend, currency).preview();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SettlementResponse commit(UUID owner, UUID friend, CommitSettlementRequest request) {
    authorize(owner, friend);
    var requested = request.allocations().stream().map(Allocation::key).toList();
    var batch = UUID.randomUUID();
    String intent = mapper.writeValueAsString(intentOf(friend, request, requested));
    int claimed =
        jdbc.sql(
                """
        INSERT INTO settlement_batches(id,actor_id,friend_account_id,currency,operation_id,intent)
        VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING
        """)
            .params(batch, owner, friend, request.currency().name(), request.operationId(), intent)
            .update();
    if (claimed == 0) return replay(owner, request, intent);

    var before = compute(owner, friend, request.currency());
    // Ascending UUID order, archived scopes included so a racing archive is seen after the lock.
    for (UUID scope : before.scopes()) writes.lockForMemberAllowingArchived(scope, owner);
    var locked = compute(owner, friend, request.currency());
    if (!locked.scopes().equals(before.scopes())
        || !locked.preview().allocations().stream().map(Allocation::key).toList().equals(requested))
      throw new ConflictException(
          "Balances changed since the preview. Review the settlement again.");

    var recorded = new ArrayList<Allocation>();
    for (var a : locked.preview().allocations()) {
      var payment =
          payments.recordPayment(
              owner,
              a.scopeId(),
              new RecordPaymentRequest(
                  a.payerId(),
                  a.payeeId(),
                  null,
                  null,
                  a.amountMinor(),
                  request.currency(),
                  request.paidOn(),
                  request.method(),
                  request.note()));
      jdbc.sql(
              "INSERT INTO settlement_allocations(batch_id,payment_id,group_id,amount_cents) VALUES (?,?,?,?)")
          .params(batch, payment.id(), a.scopeId(), a.amountMinor())
          .update();
      recorded.add(
          new Allocation(
              a.scopeId(), a.groupName(), a.payerId(), a.payeeId(), a.amountMinor(), payment.id()));
    }
    var response =
        new SettlementResponse(batch, request.currency(), recorded, locked.preview().netMinor());
    jdbc.sql("UPDATE settlement_batches SET response=?::jsonb WHERE id=?")
        .params(mapper.writeValueAsString(response), batch)
        .update();
    return response;
  }

  private SettlementResponse replay(UUID owner, CommitSettlementRequest request, String intent) {
    var stored =
        jdbc.sql(
                "SELECT intent::text, response::text FROM settlement_batches WHERE actor_id=? AND operation_id=?")
            .params(owner, request.operationId())
            .query((rs, row) -> new String[] {rs.getString(1), rs.getString(2)})
            .single();
    if (!mapper.readTree(stored[0]).equals(mapper.readTree(intent)))
      throw new ConflictException("Operation ID already used for a different settlement");
    return mapper.readValue(stored[1], SettlementResponse.class);
  }

  private Object intentOf(UUID friend, CommitSettlementRequest r, List<Allocation> allocations) {
    return new java.util.LinkedHashMap<String, Object>() {
      {
        put("friendId", friend);
        put("currency", r.currency());
        put("allocations", allocations);
        put("paidOn", r.paidOn());
        put("method", r.method());
        put("note", r.note());
      }
    };
  }

  /** Same gate as the personal ledger: only a saved friend, 403 otherwise. */
  private void authorize(UUID owner, UUID friend) {
    ParticipantContract.requireEnabled();
    if (friend.equals(owner))
      throw new BadRequestException("Choose another person to settle with.");
    if (friends.find(owner, friend).isEmpty())
      throw new ForbiddenException("Save this person as a friend to settle with them.");
  }

  private boolean shares(UUID group, UUID friend) {
    return jdbc.sql("SELECT EXISTS(SELECT 1 FROM group_memberships WHERE group_id=? AND user_id=?)")
        .params(group, friend)
        .query(Boolean.class)
        .single();
  }

  private Computed compute(UUID owner, UUID friend, LedgerCurrency currency) {
    var personal = personalLedgers.find(owner, friend);
    var groups =
        memberships.findGroupSummariesForUser(owner).stream()
            .filter(g -> shares(g.getId(), friend))
            .sorted(Comparator.comparing(g -> g.getId()))
            .toList();
    var allocations = new ArrayList<Allocation>();
    var skipped = new ArrayList<SettlementPreview.Skipped>();
    long net = 0;
    for (var g : groups) {
      long owed =
          pairwise.compute(owner, g.getId()).stream()
              .filter(p -> p.user().id().equals(friend) && p.currency() == currency)
              .mapToLong(p -> p.netCents())
              .reduce(0, Math::addExact);
      if (owed == 0) continue;
      String name = personal.filter(g.getId()::equals).isPresent() ? null : g.getName();
      if (g.getArchivedAt() != null) {
        skipped.add(new SettlementPreview.Skipped(g.getId(), name, "Archived group"));
        continue;
      }
      allocations.add(
          owed > 0
              ? new Allocation(g.getId(), name, friend, owner, owed, null)
              : new Allocation(g.getId(), name, owner, friend, -owed, null));
      net = Math.addExact(net, owed);
    }
    return new Computed(
        groups.stream().map(g -> g.getId()).toList(),
        new SettlementPreview(currency, allocations, skipped, net));
  }
}
