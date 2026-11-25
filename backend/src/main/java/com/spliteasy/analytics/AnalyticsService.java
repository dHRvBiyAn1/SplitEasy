package com.spliteasy.analytics;

import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.repository.PersonalLedgerRepository;
import com.spliteasy.service.MembershipGuard;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * P04 read model over the same active entries as the H05 CSV (expenses, plus refunds on their own
 * refund date), so totals reconcile with an export of the same filter.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {
  private final JdbcClient jdbc;
  private final MembershipGuard membershipGuard;
  private final PersonalLedgerRepository personalLedgers;

  private static final String PERSON_NAME =
      """
      (SELECT CASE WHEN x.scope_group_id IS NULL THEN COALESCE(u.display_name,x.display_name) \
      ELSE x.display_name END FROM participants x \
      LEFT JOIN participant_account_links l ON l.participant_id=x.id \
      LEFT JOIN users u ON u.id=l.user_id WHERE x.id=t.key::uuid)""";

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public AnalyticsResponse analytics(
      UUID viewer, LocalDate from, LocalDate to, UUID groupId, String groupBy) {
    String keyExpr =
        switch (groupBy) {
          case "category" -> "category";
          case "month" -> "to_char(d,'YYYY-MM')";
          case "person" -> "person::text";
          default -> throw new BadRequestException("groupBy must be category, month or person");
        };
    if (from != null && to != null && from.isAfter(to))
      throw new BadRequestException("from must not be after to");
    if (groupId != null) {
      membershipGuard.requireMember(groupId, viewer);
      if (personalLedgers.isMemberOfPersonal(groupId, viewer))
        throw new NotFoundException("Group not found");
    }
    List<Object> args = new ArrayList<>();
    args.add(viewer);
    args.add(viewer);
    String scopeGroup = "";
    if (groupId != null) {
      scopeGroup = " AND g.id=?";
      args.add(groupId);
    }
    String dates = "";
    if (from != null) {
      dates += " AND d >= ?";
      args.add(from);
    }
    if (to != null) {
      dates += " AND d <= ?";
      args.add(to);
    }
    String label = groupBy.equals("person") ? PERSON_NAME : "t.key";
    String sql =
        """
        WITH me AS (SELECT participant_id FROM participant_account_links WHERE user_id=?),
        scope AS (SELECT g.id FROM group_memberships gm JOIN groups g ON g.id=gm.group_id
                  WHERE gm.user_id=? AND g.deleted_at IS NULL%s),
        entries AS (
          SELECT e.currency,e.spent_on AS d,e.category,e.paid_by AS person,e.amount_cents AS total,
                 COALESCE((SELECT sum(ep.share_cents) FROM expense_participants ep
                           WHERE ep.expense_id=e.id AND ep.user_id IN (SELECT participant_id FROM me)),0) AS my_share,
                 COALESCE((SELECT sum(c.amount_cents) FROM expense_contributions c
                           WHERE c.expense_id=e.id AND c.participant_id IN (SELECT participant_id FROM me)),0) AS my_paid
          FROM expenses e WHERE e.deleted_at IS NULL AND e.group_id IN (SELECT id FROM scope)
          UNION ALL
          SELECT r.currency,r.refunded_on,re.category,re.paid_by,-r.amount_cents,
                 -COALESCE((SELECT sum(rp.share_cents) FROM expense_refund_participants rp
                            WHERE rp.refund_id=r.id AND rp.participant_id IN (SELECT participant_id FROM me)),0),
                 -CASE WHEN r.received_by IN (SELECT participant_id FROM me) THEN r.amount_cents ELSE 0 END
          FROM expense_refunds r JOIN expenses re ON re.id=r.expense_id
          WHERE r.deleted_at IS NULL AND re.deleted_at IS NULL AND r.group_id IN (SELECT id FROM scope)),
        t AS (SELECT currency,%s AS key,sum(total)::bigint AS total,sum(my_share)::bigint AS my_share,
                     sum(my_paid)::bigint AS my_paid
              FROM entries WHERE TRUE%s GROUP BY currency,%s)
        SELECT t.currency,t.key,%s AS label,t.total,t.my_share,t.my_paid FROM t ORDER BY t.currency,t.key
        """
            .formatted(scopeGroup, keyExpr, dates, keyExpr, label);
    List<AnalyticsResponse.Row> rows =
        jdbc.sql(sql)
            .params(args)
            .query(
                (rs, n) ->
                    new AnalyticsResponse.Row(
                        LedgerCurrency.valueOf(rs.getString("currency")),
                        rs.getString("key"),
                        rs.getString("label"),
                        rs.getLong("total"),
                        rs.getLong("my_share"),
                        rs.getLong("my_paid")))
            .list();
    return new AnalyticsResponse(rows);
  }
}
