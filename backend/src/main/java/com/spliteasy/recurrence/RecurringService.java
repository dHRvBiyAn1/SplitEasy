package com.spliteasy.recurrence;

import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.service.ExpenseService;
import com.spliteasy.service.GroupWriteGuard;
import com.spliteasy.service.MembershipGuard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Schedule CRUD. Occurrence generation lives in {@link RecurrenceRunner}. */
@Service
@RequiredArgsConstructor
public class RecurringService {
  private static final String COLUMNS =
      "id,group_id,created_by,template::text AS template,frequency,anchor_date,time_zone,next_date,"
          + "version,status,review_reason,created_at,cancelled_at";

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final MembershipGuard membership;
  private final GroupWriteGuard writeGuard;
  private final Clock clock;
  private final ExpenseService expenses;
  private final PlatformTransactionManager transactions;

  @Transactional(readOnly = true)
  public List<ScheduleResponse> list(UUID userId, UUID groupId) {
    membership.requireMember(groupId, userId);
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM recurring_schedules WHERE group_id=? ORDER BY created_at DESC, id")
        .param(groupId)
        .query(this::map)
        .list();
  }

  @Transactional(readOnly = true)
  public ScheduleResponse get(UUID userId, UUID groupId, UUID id) {
    membership.requireMember(groupId, userId);
    return find(groupId, id, false);
  }

  public ScheduleResponse create(UUID userId, UUID groupId, ScheduleRequest request) {
    membership.requireMember(groupId, userId);
    ZoneId zone = zone(request);
    if (request.operationId() != null) {
      var original = findByOperation(userId, groupId, request.operationId());
      if (original != null) return replay(original, request, zone);
    }
    validate(userId, groupId, request);
    return write(
        status -> {
          writeGuard.lockForMember(groupId, userId);
          UUID id = UUID.randomUUID();
          int inserted =
              jdbc.sql(
                      """
              INSERT INTO recurring_schedules(id,group_id,created_by,template,frequency,anchor_date,time_zone,next_date,operation_id)
              VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,?)
              ON CONFLICT (created_by, group_id, operation_id) WHERE operation_id IS NOT NULL DO NOTHING
              """)
                  .params(id, groupId, userId, templateJson(request), request.frequency().name())
                  .param(request.anchorDate())
                  .param(zone.getId())
                  .param(request.anchorDate())
                  .param(request.operationId())
                  .update();
          if (inserted == 0) {
            return replay(findByOperation(userId, groupId, request.operationId()), request, zone);
          }
          return find(groupId, id, false);
        });
  }

  private ScheduleResponse replay(ScheduleResponse original, ScheduleRequest request, ZoneId zone) {
    boolean same =
        // Compare parsed text on both sides: valueToTree yields Long nodes that never equal Int
        // nodes.
        mapper
                .readTree(templateJson(request))
                .equals(mapper.readTree(mapper.writeValueAsString(original.template())))
            && original.frequency() == request.frequency()
            && original.anchorDate().equals(request.anchorDate())
            && original.timeZone().equals(zone.getId());
    if (!same) throw new ConflictException("Operation ID already used for a different schedule");
    return original;
  }

  private ScheduleResponse findByOperation(UUID userId, UUID groupId, UUID operationId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM recurring_schedules WHERE created_by=? AND group_id=? AND operation_id=?")
        .params(userId, groupId, operationId)
        .query(this::map)
        .optional()
        .orElse(null);
  }

  /**
   * Dry-runs the template through ExpenseService (payer, contributors, split participants and split
   * math) and rolls back, so a bad template fails now instead of at its first occurrence. Runs in
   * its own transaction, before the write takes the group lock it would otherwise wait on.
   */
  private void validate(UUID userId, UUID groupId, ScheduleRequest request) {
    CreateExpenseRequest t = request.template();
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              expenses.createExpense(
                  userId,
                  groupId,
                  new CreateExpenseRequest(
                      t.description(),
                      t.amountCents(),
                      t.paidByUserId(),
                      t.participantUserIds(),
                      t.splitType(),
                      t.splits(),
                      t.category(),
                      request.anchorDate(),
                      null,
                      null,
                      t.amountMinor(),
                      t.currency(),
                      t.contributions()));
              status.setRollbackOnly();
            });
  }

  private <T> T write(
      java.util.function.Function<org.springframework.transaction.TransactionStatus, T> body) {
    var template = new TransactionTemplate(transactions);
    template.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    return template.execute(body::apply);
  }

  /** Changes future occurrences only: past expenses are never touched; resets a halted review. */
  public ScheduleResponse update(UUID userId, UUID groupId, UUID id, ScheduleRequest request) {
    membership.requireMember(groupId, userId);
    if (request.expectedVersion() == null)
      throw new BadRequestException("expectedVersion is required");
    zone(request);
    validate(userId, groupId, request);
    return write(status -> doUpdate(userId, groupId, id, request));
  }

  private ScheduleResponse doUpdate(UUID userId, UUID groupId, UUID id, ScheduleRequest request) {
    writeGuard.lockForMember(groupId, userId);
    ScheduleResponse current = find(groupId, id, true);
    if (current.version() != request.expectedVersion())
      throw new ConflictException("This schedule changed — reload it before editing");
    if ("CANCELLED".equals(current.status()))
      throw new ConflictException("A cancelled schedule cannot be edited");
    ZoneId zone = zone(request);
    LocalDate next =
        RecurrenceCalendar.firstOnOrAfter(
            request.frequency(), request.anchorDate(), LocalDate.now(clock.withZone(zone)));
    jdbc.sql(
            """
        UPDATE recurring_schedules SET template=CAST(? AS jsonb), frequency=?, anchor_date=?,
          time_zone=?, next_date=?, status='ACTIVE', review_reason=NULL, version=version+1
        WHERE id=?
        """)
        .param(templateJson(request))
        .param(request.frequency().name())
        .param(request.anchorDate())
        .param(zone.getId())
        .param(next)
        .param(id)
        .update();
    return find(groupId, id, false);
  }

  /** Stops future occurrences and deletes nothing; allowed on an archived group. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ScheduleResponse cancel(UUID userId, UUID groupId, UUID id) {
    writeGuard.lockForMemberAllowingArchived(groupId, userId);
    find(groupId, id, true);
    jdbc.sql(
            """
        UPDATE recurring_schedules SET status='CANCELLED', review_reason=NULL,
          cancelled_at=COALESCE(cancelled_at, clock_timestamp()), version=version+1
        WHERE id=? AND status<>'CANCELLED'
        """)
        .param(id)
        .update();
    return find(groupId, id, false);
  }

  private ZoneId zone(ScheduleRequest request) {
    try {
      return ZoneId.of(request.timeZone());
    } catch (java.time.DateTimeException e) {
      throw new BadRequestException("timeZone must be an IANA zone such as Australia/Sydney");
    }
  }

  private String templateJson(ScheduleRequest request) {
    CreateExpenseRequest t = request.template();
    return mapper.writeValueAsString(
        new CreateExpenseRequest(
            t.description(),
            t.amountCents(),
            t.paidByUserId(),
            t.participantUserIds(),
            t.splitType(),
            t.splits(),
            t.category(),
            null,
            null,
            null,
            t.amountMinor(),
            t.currency(),
            t.contributions()));
  }

  private ScheduleResponse find(UUID groupId, UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM recurring_schedules WHERE id=? AND group_id=?"
                + (lock ? " FOR UPDATE" : ""))
        .params(id, groupId)
        .query(this::map)
        .optional()
        .orElseThrow(() -> new NotFoundException("Recurring expense not found"));
  }

  private ScheduleResponse map(ResultSet rs, int row) throws SQLException {
    return new ScheduleResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getObject("created_by", UUID.class),
        mapper.readValue(rs.getString("template"), CreateExpenseRequest.class),
        Frequency.valueOf(rs.getString("frequency")),
        rs.getObject("anchor_date", LocalDate.class),
        rs.getString("time_zone"),
        rs.getObject("next_date", LocalDate.class),
        rs.getLong("version"),
        rs.getString("status"),
        rs.getString("review_reason"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("cancelled_at") == null
            ? null
            : rs.getTimestamp("cancelled_at").toInstant());
  }
}
