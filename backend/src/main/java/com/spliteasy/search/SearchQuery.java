package com.spliteasy.search;

import com.spliteasy.dto.history.HistoryItem;
import com.spliteasy.dto.history.HistoryType;
import com.spliteasy.entity.LedgerCurrency;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * P01-A read model: active expenses, settlements and refunds of the caller's current, non-deleted
 * groups, ordered like H05 history. Authorization is part of the query (memberships only).
 */
@Repository
@RequiredArgsConstructor
public class SearchQuery {
  private final JdbcClient jdbc;

  /** Everything optional; amount bounds are minor units of {@code currency}, never alone. */
  public record Criteria(
      UUID groupId,
      UUID friendId,
      String text,
      LocalDate from,
      LocalDate to,
      LedgerCurrency currency,
      Long minAmount,
      Long maxAmount) {}

  public record Cursor(LocalDate financialDate, UUID id) {}

  private static final String ENTRIES =
      """
      SELECT 'EXPENSE' AS type,e.id,e.group_id,e.spent_on AS financial_date,e.description,
             e.currency,e.amount_cents AS amount_minor,
             COALESCE((SELECT max(v.recorded_at) FROM expense_revisions v WHERE v.expense_id=e.id),e.created_at) AS updated_at,
             e.description || E'\\n' || e.category AS haystack,
             ARRAY(SELECT ep.user_id FROM expense_participants ep WHERE ep.expense_id=e.id) || e.paid_by
               || ARRAY(SELECT c.participant_id FROM expense_contributions c WHERE c.expense_id=e.id) AS parties
      FROM expenses e WHERE e.deleted_at IS NULL AND e.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'PAYMENT',p.id,p.group_id,p.paid_on,%1$s || ' paid ' || %2$s,p.currency,p.amount_cents,
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.payment_id=p.id),p.created_at),
             COALESCE(p.note,''),ARRAY[p.payer_id,p.payee_id]
      FROM payments p WHERE p.deleted_at IS NULL AND p.group_id IN (SELECT id FROM scope)
      UNION ALL
      SELECT 'REFUND',r.id,r.group_id,r.refunded_on,re.description,r.currency,r.amount_cents,
             COALESCE((SELECT max(v.recorded_at) FROM ledger_revisions v WHERE v.refund_id=r.id),r.created_at),
             re.description || E'\\n' || COALESCE(r.note,''),
             ARRAY(SELECT rp.participant_id FROM expense_refund_participants rp WHERE rp.refund_id=r.id) || r.received_by
      FROM expense_refunds r JOIN expenses re ON re.id=r.expense_id
      WHERE r.deleted_at IS NULL AND re.deleted_at IS NULL AND r.group_id IN (SELECT id FROM scope)
      """;

  private static String name(String participant) {
    return """
        (SELECT CASE WHEN x.scope_group_id IS NULL THEN COALESCE(u.display_name,x.display_name) \
        ELSE x.display_name END FROM participants x \
        LEFT JOIN participant_account_links l ON l.participant_id=x.id \
        LEFT JOIN users u ON u.id=l.user_id WHERE x.id=%s)"""
        .formatted(participant);
  }

  /** Escapes LIKE metacharacters so user text is always matched literally. */
  static String like(String text) {
    return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  public List<HistoryItem> search(UUID viewer, Criteria c, Cursor after, int limit) {
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "WITH scope AS (SELECT g.id,CASE WHEN g.kind='PERSONAL' THEN NULL ELSE g.name END AS name"
                + " FROM group_memberships gm JOIN groups g ON g.id=gm.group_id"
                + " WHERE gm.user_id=? AND g.deleted_at IS NULL");
    args.add(viewer);
    if (c.groupId() != null) {
      sql.append(" AND g.id=?");
      args.add(c.groupId());
    }
    sql.append("), entries AS (")
        .append(ENTRIES.formatted(name("p.payer_id"), name("p.payee_id")))
        .append(
            ") SELECT entries.type,entries.id,entries.group_id,scope.name AS group_name,"
                + "entries.financial_date,entries.description,entries.currency,entries.amount_minor,"
                + "entries.updated_at FROM entries JOIN scope ON scope.id=entries.group_id WHERE TRUE");
    if (c.friendId() != null) {
      // A friend's entries are those both people take part in.
      for (UUID person : List.of(c.friendId(), viewer)) {
        sql.append(
            " AND entries.parties && ARRAY(SELECT participant_id FROM participant_account_links"
                + " WHERE user_id=?)");
        args.add(person);
      }
    }
    if (c.text() != null) {
      // Person names are matched on the entry's own participants, so they never widen scope.
      sql.append(
          " AND (entries.haystack || E'\\n' || COALESCE((SELECT string_agg(CASE WHEN x.scope_group_id IS NULL"
              + " THEN COALESCE(u.display_name,x.display_name) ELSE x.display_name END,E'\\n')"
              + " FROM participants x LEFT JOIN participant_account_links l ON l.participant_id=x.id"
              + " LEFT JOIN users u ON u.id=l.user_id WHERE x.id=ANY(entries.parties)),''))"
              + " ILIKE ? ESCAPE '\\'");
      args.add(like(c.text()));
    }
    if (c.from() != null) {
      sql.append(" AND entries.financial_date >= ?");
      args.add(c.from());
    }
    if (c.to() != null) {
      sql.append(" AND entries.financial_date <= ?");
      args.add(c.to());
    }
    if (c.currency() != null) {
      sql.append(" AND entries.currency = ?");
      args.add(c.currency().name());
    }
    if (c.minAmount() != null) {
      sql.append(" AND entries.amount_minor >= ?");
      args.add(c.minAmount());
    }
    if (c.maxAmount() != null) {
      sql.append(" AND entries.amount_minor <= ?");
      args.add(c.maxAmount());
    }
    if (after != null) {
      sql.append(" AND (entries.financial_date,entries.id) < (?,?)");
      args.add(after.financialDate());
      args.add(after.id());
    }
    sql.append(" ORDER BY entries.financial_date DESC,entries.id DESC LIMIT ?");
    args.add(limit);
    return jdbc.sql(sql.toString())
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
}
