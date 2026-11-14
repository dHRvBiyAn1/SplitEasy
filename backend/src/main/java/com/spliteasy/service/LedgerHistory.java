package com.spliteasy.service;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.payment.LedgerRevision;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The same snapshot mechanism {@code ExpenseHistory} gives expenses, over the two B01 resources.
 * Snapshots share the caller's transaction with the accepted financial write.
 */
@Component
@RequiredArgsConstructor
class LedgerHistory {
  /** Which {@code ledger_revisions} reference column a subject uses. */
  enum Subject {
    PAYMENT("payment_id"),
    REFUND("refund_id");

    private final String column;

    Subject(String column) {
      this.column = column;
    }
  }

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final com.spliteasy.activity.ActivityRecorder activity;

  <T> void record(
      Subject subject,
      UUID subjectId,
      long version,
      String action,
      UserSummary actor,
      UUID operationId,
      T before,
      T after) {
    jdbc.sql(
            """
        INSERT INTO ledger_revisions(id,%s,version,action,actor,operation_id,before_snapshot,after_snapshot)
        VALUES (?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb))
        """
                .formatted(subject.column))
        .params(UUID.randomUUID(), subjectId, version, action)
        .param(mapper.writeValueAsString(actor))
        .param(operationId)
        .param(before == null ? null : mapper.writeValueAsString(before))
        .param(mapper.writeValueAsString(after))
        .update();
    if (after instanceof com.spliteasy.dto.payment.PaymentResponse p)
      activity.payment(action, actor, p);
    else if (after instanceof com.spliteasy.dto.payment.RefundResponse r)
      activity.refund(action, actor, r);
  }

  <T> List<LedgerRevision<T>> list(Subject subject, UUID subjectId, Class<T> snapshotType) {
    return jdbc.sql(
            """
        SELECT action,actor,recorded_at,version,operation_id,before_snapshot,after_snapshot
        FROM ledger_revisions WHERE %s=? ORDER BY version
        """
                .formatted(subject.column))
        .param(subjectId)
        .query(
            (rs, row) ->
                new LedgerRevision<>(
                    rs.getString("action"),
                    rs.getString("actor") == null
                        ? null
                        : mapper.readValue(rs.getString("actor"), UserSummary.class),
                    rs.getTimestamp("recorded_at").toInstant(),
                    rs.getLong("version"),
                    rs.getObject("operation_id", UUID.class),
                    rs.getString("before_snapshot") == null
                        ? null
                        : mapper.readValue(rs.getString("before_snapshot"), snapshotType),
                    mapper.readValue(rs.getString("after_snapshot"), snapshotType)))
        .list();
  }
}
