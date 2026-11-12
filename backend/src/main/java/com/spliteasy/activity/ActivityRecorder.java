package com.spliteasy.activity;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.payment.PaymentResponse;
import com.spliteasy.dto.payment.RefundResponse;
import com.spliteasy.repository.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one activity event plus its recipients' notifications in the caller's transaction, so a
 * rolled-back financial write leaves neither. Called from the revision writers and GroupService.
 */
@Component
@RequiredArgsConstructor
public class ActivityRecorder {
  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final UserRepository users;

  public void expense(String action, UserSummary actor, ExpenseResponse after) {
    var summary = new LinkedHashMap<String, Object>();
    summary.put("description", after.description());
    summary.put("amountMinor", after.amountMinor());
    summary.put("currency", after.currency());
    summary.put("payer", after.paidBy());
    summary.put("version", after.version());
    record(after.groupId(), "EXPENSE_" + action, after.id(), actor, summary);
  }

  public void payment(String action, UserSummary actor, PaymentResponse after) {
    UUID group =
        jdbc.sql("SELECT group_id FROM payments WHERE id=?")
            .param(after.id())
            .query(UUID.class)
            .single();
    var summary = new LinkedHashMap<String, Object>();
    summary.put("amountMinor", after.amountMinor());
    summary.put("currency", after.currency());
    summary.put("payer", after.payer());
    summary.put("payee", after.payee());
    summary.put("version", after.version());
    record(group, "PAYMENT_" + action, after.id(), actor, summary);
  }

  public void refund(String action, UserSummary actor, RefundResponse after) {
    UUID group =
        jdbc.sql("SELECT group_id FROM expenses WHERE id=?")
            .param(after.expenseId())
            .query(UUID.class)
            .single();
    var summary = new LinkedHashMap<String, Object>();
    summary.put("amountMinor", after.amountMinor());
    summary.put("currency", after.currency());
    summary.put("payee", after.receivedBy());
    summary.put("expenseId", after.expenseId());
    summary.put("version", after.version());
    record(group, "REFUND_" + action, after.id(), actor, summary);
  }

  /** Group lifecycle and roster events; {@code member} is the person added/removed/left, if any. */
  public void group(UUID groupId, String kind, UUID actorId, String groupName, UserSummary member) {
    var summary = new LinkedHashMap<String, Object>();
    summary.put("name", groupName);
    if (member != null) summary.put("member", member);
    var actor = users.findById(actorId).map(UserSummary::from).orElse(null);
    record(groupId, kind, groupId, actor, summary);
  }

  /** Balance reminder: delivered to the debtor only, under their payments preference. */
  public void reminder(
      UUID groupId, UserSummary actor, UUID debtorId, String currency, long amountMinor) {
    var summary = new LinkedHashMap<String, Object>();
    summary.put("debtorId", debtorId);
    summary.put("currency", currency);
    summary.put("amountMinor", amountMinor);
    record(groupId, "BALANCE_REMINDER", debtorId, actor, summary, debtorId);
  }

  private void record(
      UUID groupId, String kind, UUID subjectId, UserSummary actor, Map<String, Object> summary) {
    record(groupId, kind, subjectId, actor, summary, null);
  }

  private void record(
      UUID groupId,
      String kind,
      UUID subjectId,
      UserSummary actor,
      Map<String, Object> summary,
      UUID onlyRecipient) {
    UUID event = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO activity_events(id,group_id,kind,subject_id,actor,summary)
        VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb))
        """)
        .params(event, groupId, kind, subjectId)
        .param(actor == null ? null : mapper.writeValueAsString(actor))
        .param(mapper.writeValueAsString(summary))
        .update();
    // Column comes from a closed set below, never from input.
    String preference =
        kind.startsWith("EXPENSE_")
            ? "expenses"
            : kind.startsWith("PAYMENT_")
                    || kind.startsWith("REFUND_")
                    || kind.startsWith("BALANCE_")
                ? "payments"
                : "group_changes";
    jdbc.sql(
            """
        INSERT INTO notifications(id,recipient_id,event_id)
        SELECT gen_random_uuid(), m.user_id, ?
        FROM group_memberships m LEFT JOIN notification_preferences p ON p.user_id=m.user_id
        WHERE m.group_id=? AND m.user_id IS DISTINCT FROM CAST(? AS uuid)
          AND COALESCE(p.%s, TRUE)
          AND (CAST(? AS uuid) IS NULL OR m.user_id = CAST(? AS uuid))
        ON CONFLICT DO NOTHING
        """
                .formatted(preference))
        .params(event, groupId, actor == null ? null : actor.id(), onlyRecipient, onlyRecipient)
        .update();
  }
}
