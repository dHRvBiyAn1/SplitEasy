package com.spliteasy.recurrence;

import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.NotFoundException;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ScheduleResponse create(UUID userId, UUID groupId, ScheduleRequest request) {
    writeGuard.lockForMember(groupId, userId);
    ZoneId zone = zone(request);
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO recurring_schedules(id,group_id,created_by,template,frequency,anchor_date,time_zone,next_date)
        VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?)
        """)
        .params(id, groupId, userId, templateJson(request), request.frequency().name())
        .param(request.anchorDate())
        .param(zone.getId())
        .param(request.anchorDate())
        .update();
    return find(groupId, id, false);
  }

  /** Changes future occurrences only: past expenses are never touched; resets a halted review. */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ScheduleResponse update(UUID userId, UUID groupId, UUID id, ScheduleRequest request) {
    writeGuard.lockForMember(groupId, userId);
    if (request.expectedVersion() == null)
      throw new BadRequestException("expectedVersion is required");
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
