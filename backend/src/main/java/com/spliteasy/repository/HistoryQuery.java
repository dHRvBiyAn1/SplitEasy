package com.spliteasy.repository;

import com.spliteasy.dto.history.HistoryFilter;
import com.spliteasy.dto.history.HistoryItem;
import com.spliteasy.dto.history.HistoryType;
import com.spliteasy.entity.LedgerCurrency;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * H05 read model: active expenses, settlements and refunds of the caller's current, non-deleted
 * groups in one stable {@code (financial_date DESC, id DESC)} keyset order. Authorization is part
 * of every query: rows outside the caller's memberships are never selected.
 */
@Repository
public class HistoryQuery {
  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  public HistoryQuery(JdbcClient jdbc, JdbcTemplate template) {
    this.jdbc = jdbc;
    this.template = template;
  }

  /** Position of the last row already returned; the next page starts strictly after it. */
  public record Cursor(LocalDate financialDate, UUID id) {}

  /** One participant's share of one entry, flattened for the CSV export. */
  public record ExportRow(
      HistoryType type,
      UUID transactionId,
      UUID groupId,
      String groupName,
      LocalDate financialDate,
      String description,
      LedgerCurrency currency,
      long amountMinor,
      UUID participantId,
      String participantName,
      long paidMinor,
      long owedMinor,
      long settlementMinor,
      long netMinor,
      String lastChangedBy) {}

  private static String name(String participant) {
    return """
        (SELECT CASE WHEN x.scope_group_id IS NULL THEN COALESCE(u.display_name,x.display_name) \
        ELSE x.display_name END FROM participants x \
        LEFT JOIN participant_account_links l ON l.participant_id=x.id \
        LEFT JOIN users u ON u.id=l.user_id WHERE x.id=%s)"""
        .formatted(participant);
  }

  private static String scope(UUID viewer, HistoryFilter filter, List<Object> args) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT g.id,g.name FROM group_memberships gm JOIN groups g ON g.id=gm.group_id"
                + " WHERE gm.user_id=? AND g.deleted_at IS NULL");
    args.add(viewer);
    if (filter.groupId() != null) {
      sql.append(" AND g.id=?");
      args.add(filter.groupId());
    }
    return sql.toString();
  }

  // Parties are only computed when a friend scope needs them.
  private static final String EXPENSE_PARTIES =
      ",ARRAY(SELECT ep.user_id FROM expense_participants ep WHERE ep.expense_id=e.id) || e.paid_by AS parties";
  private static final String PAYMENT_PARTIES = ",ARRAY[p.payer_id,p.payee_id]";
  private static final String REFUND_PARTIES =
      ",ARRAY(SELECT rp.participant_id FROM expense_refund_participants rp WHERE rp.refund_id=r.id) || r.received_by";

  private static final String PAGE_ENTRIES =
      """
      SELECT 'EXPENSE' AS type,e.id,e.group_id,e.spent_on AS financial_date,e.description,
             e.currency,e.amount_cents AS amount_minor,
             COALESCE((SELECT max(v.recorded_at) FROM expense_revisions v WHERE v.expense_id=e.id),e.created_at) AS updated_at
             %1$s
      FROM expenses e WHERE e.deleted_at IS NULL AND e.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'PAYMENT',p.id,p.group_id,p.paid_on,%3$s || ' paid ' || %4$s,p.currency,p.amount_cents,
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.payment_id=p.id),p.created_at)
             %2$s
      FROM payments p WHERE p.deleted_at IS NULL AND p.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'REFUND',r.id,r.group_id,r.refunded_on,re.description,r.currency,r.amount_cents,
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.refund_id=r.id),r.created_at)
             %5$s
      FROM expense_refunds r JOIN expenses re ON re.id=r.expense_id
      WHERE r.deleted_at IS NULL AND re.deleted_at IS NULL AND r.group_id IN (SELECT id FROM scope)
      """;

  private static String pageEntries(HistoryFilter filter) {
    boolean friend = filter.friendId() != null;
    return PAGE_ENTRIES.formatted(
        friend ? EXPENSE_PARTIES : "",
        friend ? PAYMENT_PARTIES : "",
        name("p.payer_id"),
        name("p.payee_id"),
        friend ? REFUND_PARTIES : "");
  }

  private static final String EXPORT_ENTRIES =
      """
      SELECT 'EXPENSE' AS type,e.id AS transaction_id,e.group_id,e.spent_on AS financial_date,
             e.description,e.currency,e.amount_cents AS amount_minor,
             ep.user_id AS participant_id,%6$s AS participant_name,
             CASE WHEN ep.user_id=e.paid_by THEN e.amount_cents ELSE 0::bigint END AS paid_minor,
             ep.share_cents AS owed_minor,0::bigint AS settlement_minor,
             CASE WHEN ep.user_id=e.paid_by THEN e.amount_cents ELSE 0::bigint END
               - ep.share_cents AS net_minor,
             (SELECT v.actor->>'displayName' FROM expense_revisions v
              WHERE v.expense_id=e.id ORDER BY v.version DESC LIMIT 1) AS last_changed_by,
             COALESCE((SELECT max(v.recorded_at) FROM expense_revisions v WHERE v.expense_id=e.id),e.created_at) AS updated_at
             %1$s
      FROM expenses e CROSS JOIN LATERAL (
        SELECT user_id,share_cents FROM expense_participants WHERE expense_id=e.id
        UNION ALL
        SELECT e.paid_by,0 WHERE NOT EXISTS
          (SELECT 1 FROM expense_participants WHERE expense_id=e.id AND user_id=e.paid_by)
      ) ep
      WHERE e.deleted_at IS NULL AND e.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'PAYMENT',p.id,p.group_id,p.paid_on,%4$s || ' paid ' || %5$s,p.currency,p.amount_cents,
             s.participant_id,%7$s,0::bigint,0::bigint,s.settlement_minor,s.settlement_minor,
             (SELECT v.actor->>'displayName' FROM ledger_revisions v
              WHERE v.payment_id=p.id ORDER BY v.version DESC LIMIT 1),
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.payment_id=p.id),p.created_at)
             %2$s
      FROM payments p
      CROSS JOIN LATERAL (VALUES (p.payer_id,p.amount_cents),(p.payee_id,-p.amount_cents))
        AS s(participant_id,settlement_minor)
      WHERE p.deleted_at IS NULL AND p.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'REFUND',r.id,r.group_id,r.refunded_on,re.description,r.currency,r.amount_cents,
             rp.participant_id,%8$s,
             CASE WHEN rp.participant_id=r.received_by THEN -r.amount_cents ELSE 0::bigint END,
             -rp.share_cents,0::bigint,
             CASE WHEN rp.participant_id=r.received_by THEN -r.amount_cents ELSE 0::bigint END
               + rp.share_cents,
             (SELECT v.actor->>'displayName' FROM ledger_revisions v
              WHERE v.refund_id=r.id ORDER BY v.version DESC LIMIT 1),
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.refund_id=r.id),r.created_at)
             %3$s
      FROM expense_refunds r
      JOIN expenses re ON re.id=r.expense_id
      JOIN expense_refund_participants rp ON rp.refund_id=r.id
      WHERE r.deleted_at IS NULL AND re.deleted_at IS NULL AND r.group_id IN (SELECT id FROM scope)
      """;

  private static String exportEntries(HistoryFilter filter) {
    boolean friend = filter.friendId() != null;
    return EXPORT_ENTRIES.formatted(
        friend ? EXPENSE_PARTIES : "",
        friend ? PAYMENT_PARTIES : "",
        friend ? REFUND_PARTIES : "",
        name("p.payer_id"),
        name("p.payee_id"),
        name("ep.user_id"),
        name("s.participant_id"),
        name("rp.participant_id"));
  }

  /** {@code WHERE} over the {@code entries} CTE; params are appended in placeholder order. */
  private static String where(
      UUID viewer, HistoryFilter filter, List<Object> args, Cursor after, Integer limit) {
    StringBuilder where = new StringBuilder(" WHERE TRUE");
    if (filter.friendId() != null) {
      // A friend's history is the entries both people take part in.
      where.append(
          " AND entries.parties && ARRAY(SELECT participant_id FROM participant_account_links"
              + " WHERE user_id=?)");
      args.add(filter.friendId());
      where.append(
          " AND entries.parties && ARRAY(SELECT participant_id FROM participant_account_links"
              + " WHERE user_id=?)");
      args.add(viewer);
    }
    if (filter.from() != null) {
      where.append(" AND entries.financial_date >= ?");
      args.add(filter.from());
    }
    if (filter.to() != null) {
      where.append(" AND entries.financial_date <= ?");
      args.add(filter.to());
    }
    if (filter.updatedAfter() != null) {
      where.append(" AND entries.updated_at >= ?");
      args.add(Timestamp.from(filter.updatedAfter()));
    }
    if (filter.updatedBefore() != null) {
      where.append(" AND entries.updated_at < ?");
      args.add(Timestamp.from(filter.updatedBefore()));
    }
    if (after != null) {
      where.append(" AND (entries.financial_date,entries.id) < (?,?)");
      args.add(after.financialDate());
      args.add(after.id());
    }
    return where.toString();
  }

  public List<HistoryItem> page(UUID viewer, HistoryFilter filter, Cursor after, int limit) {
    List<Object> args = new ArrayList<>();
    String scope = scope(viewer, filter, args);
    String where = where(viewer, filter, args, after, null);
    args.add(limit);
    String sql =
        "WITH scope AS ("
            + scope
            + "), entries AS ("
            + pageEntries(filter)
            + ") SELECT entries.type,entries.id,entries.group_id,scope.name AS group_name,"
            + "entries.financial_date,entries.description,entries.currency,entries.amount_minor,"
            + "entries.updated_at FROM entries JOIN scope ON scope.id=entries.group_id"
            + where
            + " ORDER BY entries.financial_date DESC,entries.id DESC LIMIT ?";
    return jdbc.sql(sql)
        .params(args)
        .query(
            (rs, row) ->
                new HistoryItem(
                    HistoryType.valueOf(rs.getString("type")),
                    rs.getObject("id", UUID.class),
                    rs.getObject("group_id", UUID.class),
                    rs.getString("group_name"),
                    rs.getObject("financial_date", LocalDate.class),
                    rs.getString("description"),
                    LedgerCurrency.valueOf(rs.getString("currency")),
                    rs.getLong("amount_minor"),
                    rs.getTimestamp("updated_at").toInstant()))
        .list();
  }

  /**
   * Fully filtered export rows, streamed through a cursor so a large history never materializes in
   * memory. The caller owns the surrounding read transaction.
   */
  public void export(UUID viewer, HistoryFilter filter, Consumer<ExportRow> handler) {
    List<Object> args = new ArrayList<>();
    String scope = scope(viewer, filter, args);
    String where = where(viewer, filter, args, null, null);
    String sql =
        "WITH scope AS ("
            + scope
            + "), entries AS ("
            + exportEntries(filter)
            + ") SELECT entries.type,entries.transaction_id,entries.group_id,scope.name AS group_name,"
            + "entries.financial_date,entries.description,entries.currency,entries.amount_minor,"
            + "entries.participant_id,entries.participant_name,entries.paid_minor,entries.owed_minor,"
            + "entries.settlement_minor,entries.net_minor,entries.last_changed_by"
            + " FROM entries JOIN scope ON scope.id=entries.group_id"
            + where
            + " ORDER BY entries.financial_date DESC,entries.transaction_id DESC,"
            + "entries.participant_name,entries.participant_id";
    template.query(
        connection -> {
          var statement = connection.prepareStatement(sql);
          statement.setFetchSize(500);
          for (int i = 0; i < args.size(); i++) statement.setObject(i + 1, args.get(i));
          return statement;
        },
        rs -> {
          while (rs.next()) handler.accept(exportRow(rs));
          return null;
        });
  }

  private static ExportRow exportRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ExportRow(
        HistoryType.valueOf(rs.getString("type")),
        rs.getObject("transaction_id", UUID.class),
        rs.getObject("group_id", UUID.class),
        rs.getString("group_name"),
        rs.getObject("financial_date", LocalDate.class),
        rs.getString("description"),
        LedgerCurrency.valueOf(rs.getString("currency")),
        rs.getLong("amount_minor"),
        rs.getObject("participant_id", UUID.class),
        rs.getString("participant_name"),
        rs.getLong("paid_minor"),
        rs.getLong("owed_minor"),
        rs.getLong("settlement_minor"),
        rs.getLong("net_minor"),
        rs.getString("last_changed_by"));
  }
}
