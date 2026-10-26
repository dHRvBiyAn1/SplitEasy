package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.ExpenseRevision;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Expense snapshots share the caller's transaction with the accepted financial write. */
@Component
@RequiredArgsConstructor
class ExpenseHistory {
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  void record(
      String action,
      UserSummary actor,
      UUID operationId,
      ExpenseResponse before,
      ExpenseResponse after) {
    jdbc.sql(
            """
        INSERT INTO expense_revisions(expense_id,version,action,actor,operation_id,before_snapshot,after_snapshot)
        VALUES (?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))
        """)
        .params(after.id(), after.version(), action, mapper.writeValueAsString(actor))
        .param(operationId)
        .param(before == null ? null : mapper.writeValueAsString(before))
        .param(mapper.writeValueAsString(after))
        .update();
  }

  List<ExpenseRevision> list(UUID expenseId) {
    return jdbc.sql(
            """
        SELECT action,actor,recorded_at,version,operation_id,before_snapshot,after_snapshot
        FROM expense_revisions WHERE expense_id=? ORDER BY version
        """)
        .param(expenseId)
        .query(
            (rs, row) ->
                new ExpenseRevision(
                    rs.getString("action"),
                    rs.getString("actor") == null
                        ? null
                        : mapper.readValue(rs.getString("actor"), UserSummary.class),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getLong("version"),
                    rs.getObject("operation_id", UUID.class),
                    rs.getString("before_snapshot") == null
                        ? null
                        : mapper.readValue(rs.getString("before_snapshot"), ExpenseResponse.class),
                    mapper.readValue(rs.getString("after_snapshot"), ExpenseResponse.class)))
        .list();
  }
}
