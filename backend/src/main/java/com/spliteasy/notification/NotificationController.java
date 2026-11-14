package com.spliteasy.notification;

import com.spliteasy.activity.ActivityItem;
import com.spliteasy.config.CurrentUserId;
import com.spliteasy.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Own-rows-only in-app notifications and the three delivery preferences. */
@RestController
@RequiredArgsConstructor
public class NotificationController {
  public record NotificationItem(UUID id, Instant readAt, Instant createdAt, ActivityItem event) {}

  public record NotificationPage(List<NotificationItem> items, String nextCursor) {}

  public record Preferences(
      @NotNull Boolean expenses, @NotNull Boolean payments, @NotNull Boolean groupChanges) {}

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  @GetMapping("/api/notifications")
  @Transactional(readOnly = true)
  public NotificationPage list(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(required = false) Integer limit) {
    int size = Math.max(1, Math.min(limit == null ? 50 : limit, 100));
    long before;
    try {
      before = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : Long.parseLong(cursor);
    } catch (NumberFormatException e) {
      throw new com.spliteasy.exception.BadRequestException("Invalid cursor");
    }
    var rows =
        jdbc.sql(
                """
        SELECT n.id AS nid,n.read_at,n.created_at,
          e.id,e.seq,e.group_id,CASE WHEN g.kind='PERSONAL' THEN NULL ELSE g.name END AS group_name,
          e.kind,e.subject_id,e.actor,e.summary,e.occurred_at
        FROM notifications n JOIN activity_events e ON e.id=n.event_id JOIN groups g ON g.id=e.group_id
        WHERE n.recipient_id=? AND e.seq<? AND (NOT ? OR n.read_at IS NULL)
        ORDER BY e.seq DESC LIMIT ?
        """)
            .params(userId, before, unreadOnly, size + 1)
            .query(
                (rs, i) ->
                    new NotificationItem(
                        rs.getObject("nid", UUID.class),
                        rs.getTimestamp("read_at") == null
                            ? null
                            : rs.getTimestamp("read_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        new ActivityItem(
                            rs.getObject("id", UUID.class),
                            rs.getLong("seq"),
                            rs.getObject("group_id", UUID.class),
                            rs.getString("group_name"),
                            rs.getString("kind"),
                            rs.getObject("subject_id", UUID.class),
                            rs.getString("actor") == null
                                ? null
                                : mapper.readTree(rs.getString("actor")),
                            mapper.readTree(rs.getString("summary")),
                            rs.getTimestamp("occurred_at").toInstant())))
            .list();
    boolean more = rows.size() > size;
    var items = more ? rows.subList(0, size) : rows;
    return new NotificationPage(
        items, more ? String.valueOf(items.get(size - 1).event().seq()) : null);
  }

  @GetMapping("/api/notifications/unread-count")
  public Map<String, Long> unread(@CurrentUserId UUID userId) {
    return Map.of(
        "count",
        jdbc.sql("SELECT count(*) FROM notifications WHERE recipient_id=? AND read_at IS NULL")
            .param(userId)
            .query(Long.class)
            .single());
  }

  @PostMapping("/api/notifications/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Transactional
  public void read(@CurrentUserId UUID userId, @PathVariable UUID id) {
    int updated =
        jdbc.sql(
                "UPDATE notifications SET read_at=COALESCE(read_at,clock_timestamp()) WHERE id=? AND recipient_id=?")
            .params(id, userId)
            .update();
    if (updated == 0) throw new NotFoundException("Notification not found");
  }

  @PostMapping("/api/notifications/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void readAll(@CurrentUserId UUID userId) {
    jdbc.sql(
            "UPDATE notifications SET read_at=clock_timestamp() WHERE recipient_id=? AND read_at IS NULL")
        .param(userId)
        .update();
  }

  @GetMapping("/api/me/notification-preferences")
  public Preferences preferences(@CurrentUserId UUID userId) {
    return jdbc.sql(
            "SELECT expenses,payments,group_changes FROM notification_preferences WHERE user_id=?")
        .param(userId)
        .query(
            (rs, i) ->
                new Preferences(
                    rs.getBoolean("expenses"),
                    rs.getBoolean("payments"),
                    rs.getBoolean("group_changes")))
        .optional()
        .orElse(new Preferences(true, true, true));
  }

  @PutMapping("/api/me/notification-preferences")
  public Preferences save(@CurrentUserId UUID userId, @Valid @RequestBody Preferences request) {
    jdbc.sql(
            """
        INSERT INTO notification_preferences(user_id,expenses,payments,group_changes) VALUES (?,?,?,?)
        ON CONFLICT (user_id) DO UPDATE SET expenses=EXCLUDED.expenses,
          payments=EXCLUDED.payments, group_changes=EXCLUDED.group_changes
        """)
        .params(userId, request.expenses(), request.payments(), request.groupChanges())
        .update();
    return request;
  }
}
