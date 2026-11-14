package com.spliteasy.recurrence;

import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.service.ExpenseService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates due occurrences. Safe to run on several instances or after a restart: each occurrence
 * is one transaction keyed by (schedule, date), and the group row is locked first (the same order
 * ExpenseService uses), so an occurrence can never produce two expenses.
 */
@Component
@Slf4j
public class RecurrenceRunner {
  static final int CATCH_UP_DAYS = 30;
  static final int MAX_CREATED_PER_RUN = 31;
  // A far-past anchor must not hold one run for years of skipped days; the next run resumes.
  static final int MAX_SKIPPED_PER_RUN = 400;

  private enum Step {
    NONE,
    SKIPPED,
    CREATED
  }

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final ExpenseService expenses;
  private final Clock clock;
  private final TransactionTemplate tx;

  public RecurrenceRunner(
      JdbcClient jdbc,
      ObjectMapper mapper,
      ExpenseService expenses,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.expenses = expenses;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactions);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT1M")
  public void run() {
    // Local dates run at most one day ahead of UTC; the per-schedule zone check is exact.
    List<UUID> due =
        jdbc.sql("SELECT id FROM recurring_schedules WHERE status='ACTIVE' AND next_date <= ?")
            .param(LocalDate.now(clock).plusDays(1))
            .query(UUID.class)
            .list();
    for (UUID id : due) {
      try {
        runSchedule(id);
      } catch (RuntimeException e) {
        log.error("Recurring schedule {} failed; it will be retried next run", id, e);
      }
    }
  }

  private void runSchedule(UUID id) {
    int created = 0;
    int skipped = 0;
    while (created < MAX_CREATED_PER_RUN && skipped < MAX_SKIPPED_PER_RUN) {
      Step step;
      try {
        step = tx.execute(status -> step(id));
      } catch (ForbiddenException | NotFoundException | ConflictException | BadRequestException e) {
        tx.executeWithoutResult(status -> halt(id, e.getMessage()));
        return;
      }
      if (step == Step.NONE) return;
      if (step == Step.CREATED) created++;
      else skipped++;
    }
  }

  private Step step(UUID id) {
    UUID groupId =
        jdbc.sql("SELECT group_id FROM recurring_schedules WHERE id=?")
            .param(id)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (groupId == null) return Step.NONE;
    jdbc.sql("SELECT id FROM groups WHERE id=? FOR UPDATE").param(groupId).query(UUID.class).list();
    var row =
        jdbc.sql(
                """
            SELECT created_by, template::text, frequency, anchor_date, time_zone, next_date
            FROM recurring_schedules WHERE id=? AND status='ACTIVE' FOR UPDATE
            """)
            .param(id)
            .query(
                (rs, n) ->
                    new Object[] {
                      rs.getObject(1, UUID.class),
                      rs.getString(2),
                      Frequency.valueOf(rs.getString(3)),
                      rs.getObject(4, LocalDate.class),
                      rs.getString(5),
                      rs.getObject(6, LocalDate.class)
                    })
            .optional()
            .orElse(null);
    if (row == null) return Step.NONE;
    UUID creator = (UUID) row[0];
    Frequency frequency = (Frequency) row[2];
    LocalDate anchor = (LocalDate) row[3];
    LocalDate date = (LocalDate) row[5];
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of((String) row[4])));
    if (date.isAfter(today)) return Step.NONE;
    LocalDate next = RecurrenceCalendar.next(frequency, anchor, date);

    if (date.isBefore(today.minusDays(CATCH_UP_DAYS))) {
      insertOccurrence(id, date, "SKIPPED");
      advance(id, next);
      return Step.SKIPPED;
    }
    if (insertOccurrence(id, date, "CREATED") == 0) {
      advance(id, next);
      return Step.SKIPPED;
    }
    CreateExpenseRequest t = mapper.readValue((String) row[1], CreateExpenseRequest.class);
    var expense =
        expenses.createExpense(
            creator,
            groupId,
            new CreateExpenseRequest(
                t.description(),
                t.amountCents(),
                t.paidByUserId(),
                t.participantUserIds(),
                t.splitType(),
                t.splits(),
                t.category(),
                date,
                null,
                null,
                t.amountMinor(),
                t.currency(),
                t.contributions()));
    jdbc.sql(
            "UPDATE recurring_occurrences SET expense_id=? WHERE schedule_id=? AND occurrence_date=?")
        .params(expense.id(), id, date)
        .update();
    advance(id, next);
    return Step.CREATED;
  }

  /** Records the blocked occurrence as FAILED and halts the schedule until it is edited. */
  private void halt(UUID id, String reason) {
    var row =
        jdbc.sql(
                "SELECT frequency, anchor_date, next_date FROM recurring_schedules WHERE id=? AND status='ACTIVE' FOR UPDATE")
            .param(id)
            .query(
                (rs, n) ->
                    new Object[] {
                      Frequency.valueOf(rs.getString(1)),
                      rs.getObject(2, LocalDate.class),
                      rs.getObject(3, LocalDate.class)
                    })
            .optional()
            .orElse(null);
    if (row == null) return;
    LocalDate date = (LocalDate) row[2];
    insertOccurrence(id, date, "FAILED");
    jdbc.sql(
            "UPDATE recurring_schedules SET status='NEEDS_REVIEW', review_reason=?, next_date=? WHERE id=?")
        .param(
            reason == null
                ? "The expense could not be created"
                : reason.substring(0, Math.min(400, reason.length())))
        .param(RecurrenceCalendar.next((Frequency) row[0], (LocalDate) row[1], date))
        .param(id)
        .update();
    log.warn("Recurring schedule {} needs review: {}", id, reason);
  }

  private int insertOccurrence(UUID id, LocalDate date, String status) {
    return jdbc.sql(
            "INSERT INTO recurring_occurrences(schedule_id,occurrence_date,status) VALUES (?,?,?) ON CONFLICT DO NOTHING")
        .params(id, date, status)
        .update();
  }

  private void advance(UUID id, LocalDate next) {
    jdbc.sql("UPDATE recurring_schedules SET next_date=? WHERE id=?").params(next, id).update();
  }
}
