package com.spliteasy.comment;

import com.spliteasy.activity.ActivityRecorder;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.service.GroupWriteGuard;
import com.spliteasy.service.MembershipGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Comments on a live expense, readable by every member; only the author removes their own. */
@Service
@RequiredArgsConstructor
public class CommentService {
  private final JdbcClient jdbc;
  private final MembershipGuard membership;
  private final GroupWriteGuard writeGuard;
  private final ActivityRecorder recorder;

  @Transactional(readOnly = true)
  public List<CommentResponse> list(UUID userId, UUID groupId, UUID expenseId) {
    membership.requireMember(groupId, userId);
    requireExpense(groupId, expenseId);
    return jdbc.sql(
            """
        SELECT c.id, c.body, c.created_at, u.id AS uid, u.email, u.display_name
        FROM expense_comments c JOIN users u ON u.id = c.author_id
        WHERE c.expense_id = ? AND c.deleted_at IS NULL ORDER BY c.created_at, c.id
        """)
        .param(expenseId)
        .query(
            (rs, i) ->
                new CommentResponse(
                    rs.getObject("id", UUID.class),
                    expenseId,
                    new UserSummary(
                        rs.getObject("uid", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name")),
                    rs.getString("body"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public CommentResponse create(UUID userId, UUID groupId, UUID expenseId, CommentRequest request) {
    writeGuard.lockForMember(groupId, userId);
    String description = requireExpense(groupId, expenseId);
    UUID id = UUID.randomUUID();
    OffsetDateTime created =
        jdbc.sql(
                "INSERT INTO expense_comments(id,expense_id,author_id,body) VALUES (?,?,?,?) RETURNING created_at")
            .params(id, expenseId, userId, request.body().strip())
            .query(OffsetDateTime.class)
            .single();
    UserSummary author =
        jdbc.sql("SELECT id, email, display_name FROM users WHERE id = ?")
            .param(userId)
            .query(
                (rs, i) ->
                    new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name")))
            .single();
    recorder.comment(groupId, author, id, expenseId, description);
    return new CommentResponse(id, expenseId, author, request.body().strip(), created.toInstant());
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(UUID userId, UUID groupId, UUID expenseId, UUID commentId) {
    writeGuard.lockForMember(groupId, userId);
    requireExpense(groupId, expenseId);
    UUID author =
        jdbc.sql(
                "SELECT author_id FROM expense_comments WHERE id=? AND expense_id=? AND deleted_at IS NULL")
            .params(commentId, expenseId)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Comment not found"));
    if (!author.equals(userId))
      throw new ForbiddenException("You can only delete your own comment");
    jdbc.sql("UPDATE expense_comments SET deleted_at = clock_timestamp() WHERE id = ?")
        .param(commentId)
        .update();
  }

  /** The expense must be live and in this group; returns its description for the event. */
  private String requireExpense(UUID groupId, UUID expenseId) {
    return jdbc.sql(
            "SELECT description FROM expenses WHERE id=? AND group_id=? AND deleted_at IS NULL")
        .params(expenseId, groupId)
        .query(String.class)
        .optional()
        .orElseThrow(() -> new NotFoundException("Expense not found"));
  }
}
