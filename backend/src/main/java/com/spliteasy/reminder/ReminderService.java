package com.spliteasy.reminder;

import com.spliteasy.activity.ActivityRecorder;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.service.ObligationProjectionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** In-app nudge from a creditor to one debtor; at most one per pair and currency per day. */
@Service
@RequiredArgsConstructor
public class ReminderService {
  private final ObligationProjectionService obligations;
  private final ActivityRecorder recorder;
  private final UserRepository users;
  private final JdbcClient jdbc;
  private final PlatformTransactionManager transactions;

  // Not transactional: the projection owns its REPEATABLE_READ snapshot and cannot be upgraded.
  public ReminderResponse remind(UUID userId, UUID groupId, ReminderRequest request) {
    long owed =
        obligations.forGroup(userId, groupId).stream()
            .filter(
                o ->
                    userId.equals(o.to().accountId())
                        && request.debtorId().equals(o.from().accountId())
                        && o.currency() == request.currency())
            .mapToLong(o -> o.amountMinor())
            .sum();
    if (owed <= 0) throw new ConflictException("That person does not owe you in this currency");
    UserSummary actor = users.findById(userId).map(UserSummary::from).orElseThrow();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                  .param("reminder:" + groupId + userId + request.debtorId() + request.currency())
                  .query()
                  .listOfRows();
              boolean recent =
                  jdbc.sql(
                          """
                  SELECT EXISTS (SELECT 1 FROM activity_events
                    WHERE group_id=? AND kind='BALANCE_REMINDER'
                      AND actor->>'id'=? AND summary->>'debtorId'=? AND summary->>'currency'=?
                      AND occurred_at > now() - interval '24 hours')
                  """)
                      .params(
                          groupId,
                          userId.toString(),
                          request.debtorId().toString(),
                          request.currency().name())
                      .query(Boolean.class)
                      .single();
              if (recent) throw new ConflictException("You already sent this reminder today");
              recorder.reminder(
                  groupId, actor, request.debtorId(), request.currency().name(), owed);
            });
    return new ReminderResponse(request.debtorId(), request.currency(), owed);
  }
}
