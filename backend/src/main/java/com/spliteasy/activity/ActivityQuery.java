package com.spliteasy.activity;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Shared event projection and keyset paging (seq descending) for activity and notifications. */
@Component
@RequiredArgsConstructor
class ActivityQuery {
  static final String COLUMNS =
      """
      e.id,e.seq,e.group_id,CASE WHEN g.kind='PERSONAL' THEN NULL ELSE g.name END AS group_name,
      e.kind,e.subject_id,e.actor,e.summary,e.occurred_at
      """;

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  static int clamp(Integer limit) {
    return limit == null ? 50 : Math.max(1, Math.min(limit, 100));
  }

  static long cursor(String cursor) {
    if (cursor == null || cursor.isBlank()) return Long.MAX_VALUE;
    try {
      return Long.parseLong(cursor);
    } catch (NumberFormatException e) {
      throw new com.spliteasy.exception.BadRequestException("Invalid cursor");
    }
  }

  ActivityItem item(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ActivityItem(
        rs.getObject("id", java.util.UUID.class),
        rs.getLong("seq"),
        rs.getObject("group_id", java.util.UUID.class),
        rs.getString("group_name"),
        rs.getString("kind"),
        rs.getObject("subject_id", java.util.UUID.class),
        rs.getString("actor") == null ? null : mapper.readTree(rs.getString("actor")),
        mapper.readTree(rs.getString("summary")),
        rs.getTimestamp("occurred_at").toInstant());
  }

  List<ActivityItem> feed(java.util.UUID viewer, java.util.UUID groupId, long before, int limit) {
    var sql =
        new StringBuilder(
            "SELECT "
                + COLUMNS
                + """
                FROM activity_events e JOIN groups g ON g.id=e.group_id
                WHERE g.deleted_at IS NULL AND e.seq < ?
                  AND EXISTS(SELECT 1 FROM group_memberships m WHERE m.group_id=e.group_id AND m.user_id=?)
                """);
    var params = new ArrayList<Object>(List.of(before, viewer));
    if (groupId != null) {
      sql.append(" AND e.group_id=?");
      params.add(groupId);
    }
    sql.append(" ORDER BY e.seq DESC LIMIT ?");
    params.add(limit + 1);
    return jdbc.sql(sql.toString()).params(params).query((rs, i) -> item(rs)).list();
  }
}
