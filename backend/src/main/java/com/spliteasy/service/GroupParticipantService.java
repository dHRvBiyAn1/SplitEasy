package com.spliteasy.service;

import com.spliteasy.config.ParticipantContract;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.participant.CreateParticipantRequest;
import com.spliteasy.exception.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class GroupParticipantService {
  private final GroupWriteGuard writes;
  private final FinancialParticipantService financial;
  private final BalanceService balances;
  private final ObligationProjectionService obligations;
  private final CreateOperations operations;
  private final JdbcClient jdbc;

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ParticipantSummary create(UUID actor, UUID group, CreateParticipantRequest request) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    String name = request.displayName().trim();
    if (name.isEmpty()) throw new BadRequestException("Enter a participant name");
    return operations.create(
        actor,
        "participant",
        group,
        request.operationId(),
        Map.of("displayName", name),
        ParticipantSummary.class,
        r -> {},
        () -> {
          UUID id = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO participants(id,display_name,created_at,scope_group_id) VALUES(?,?,CURRENT_TIMESTAMP,?)")
              .params(id, name, group)
              .update();
          jdbc.sql(
                  "INSERT INTO group_participants(group_id,participant_id,active,added_at) VALUES(?,?,true,CURRENT_TIMESTAMP)")
              .params(group, id)
              .update();
          return new ParticipantSummary(id, null, name, id, null);
        });
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void remove(UUID actor, UUID group, UUID id) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    var person = financial.originals(group).get(id);
    if (person == null || !financial.active(group).contains(id))
      throw new NotFoundException("Participant not found");
    if (person.accountId() != null)
      throw new ConflictException("Remove this account through group membership instead");
    if (balances.computeBalances(actor, group).balances().stream()
            .anyMatch(b -> b.user().id().equals(id) && b.netMinor() != 0)
        || obligations.snapshot(actor, group).stream()
            .anyMatch(o -> o.from().id().equals(id) || o.to().id().equals(id)))
      throw new ConflictException(
          "Settle every currency and obligation before removing this participant");
    jdbc.sql("UPDATE group_participants SET active=false WHERE group_id=? AND participant_id=?")
        .params(group, id)
        .update();
    jdbc.sql(
            "UPDATE group_invitations SET revoked_at=clock_timestamp() WHERE group_id=? AND participant_id=? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>clock_timestamp()")
        .params(group, id)
        .update();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ParticipantSummary reactivate(UUID actor, UUID group, UUID id) {
    writes.lockForMember(group, actor);
    ParticipantContract.requireEnabled();
    var person = financial.originals(group).get(id);
    if (person == null) throw new NotFoundException("Participant not found");
    if (person.accountId() != null)
      throw new ConflictException(
          "Re-add the registered account to restore its financial identities");
    boolean scoped =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM participants WHERE id=? AND scope_group_id=?)")
            .params(id, group)
            .query(Boolean.class)
            .single();
    if (!scoped) throw new NotFoundException("Participant not found");
    jdbc.sql("UPDATE group_participants SET active=true WHERE group_id=? AND participant_id=?")
        .params(group, id)
        .update();
    return person;
  }
}
