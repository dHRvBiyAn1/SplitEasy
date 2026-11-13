package com.spliteasy.attachment;

import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import com.spliteasy.exception.ForbiddenException;
import com.spliteasy.exception.NotFoundException;
import com.spliteasy.service.GroupWriteGuard;
import com.spliteasy.service.MembershipGuard;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Attachments on a live expense, stored in PostgreSQL. Upload is a trust boundary: the stored type
 * comes from the file's magic bytes, never the client header, and the client's file name is
 * display-only.
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {
  public static final long MAX_BYTES = 5L * 1024 * 1024;
  static final int MAX_PER_EXPENSE = 10;

  public record Download(String fileName, String contentType, byte[] content) {}

  private final JdbcClient jdbc;
  private final MembershipGuard membership;
  private final GroupWriteGuard writeGuard;

  @Transactional(readOnly = true)
  public List<AttachmentResponse> list(UUID userId, UUID groupId, UUID expenseId) {
    membership.requireMember(groupId, userId);
    requireExpense(groupId, expenseId);
    return jdbc.sql(
            SELECT + " WHERE a.expense_id = ? AND a.deleted_at IS NULL ORDER BY a.created_at, a.id")
        .param(expenseId)
        .query(AttachmentService::row)
        .list();
  }

  @Transactional(readOnly = true)
  public Download download(UUID userId, UUID groupId, UUID expenseId, UUID id) {
    membership.requireMember(groupId, userId);
    requireExpense(groupId, expenseId);
    return jdbc.sql(
            "SELECT file_name, content_type, content FROM attachments WHERE id=? AND expense_id=? AND deleted_at IS NULL")
        .params(id, expenseId)
        .query(
            (rs, i) ->
                new Download(
                    rs.getString("file_name"),
                    rs.getString("content_type"),
                    rs.getBytes("content")))
        .optional()
        .orElseThrow(() -> new NotFoundException("Attachment not found"));
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AttachmentResponse upload(UUID userId, UUID groupId, UUID expenseId, MultipartFile file) {
    writeGuard.lockForMember(groupId, userId);
    requireExpense(groupId, expenseId);
    if (file.getSize() > MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Files can be at most 5 MB");
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String type = detect(bytes);
    if (type == null)
      throw new BadRequestException("Only JPEG, PNG, WebP and PDF files can be attached");
    // The group lock above serializes uploads, so this count cannot be raced past the limit.
    long live =
        jdbc.sql("SELECT count(*) FROM attachments WHERE expense_id=? AND deleted_at IS NULL")
            .param(expenseId)
            .query(Long.class)
            .single();
    if (live >= MAX_PER_EXPENSE)
      throw new ConflictException("An expense can have at most 10 attachments");
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
        INSERT INTO attachments(id,expense_id,uploaded_by,file_name,content_type,size_bytes,sha256,content)
        VALUES (?,?,?,?,?,?,?,?)
        """)
        .params(
            id,
            expenseId,
            userId,
            cleanName(file.getOriginalFilename()),
            type,
            bytes.length,
            sha256(bytes),
            bytes)
        .update();
    var created =
        jdbc.sql(SELECT + " WHERE a.id = ?").param(id).query(AttachmentService::row).single();
    return created;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void delete(UUID userId, UUID groupId, UUID expenseId, UUID id) {
    writeGuard.lockForMember(groupId, userId);
    requireExpense(groupId, expenseId);
    UUID uploader =
        jdbc.sql(
                "SELECT uploaded_by FROM attachments WHERE id=? AND expense_id=? AND deleted_at IS NULL")
            .params(id, expenseId)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new NotFoundException("Attachment not found"));
    if (!uploader.equals(userId))
      throw new ForbiddenException("You can only delete your own attachment");
    jdbc.sql("UPDATE attachments SET deleted_at = clock_timestamp() WHERE id = ?")
        .param(id)
        .update();
  }

  private static final String SELECT =
      """
      SELECT a.id, a.expense_id, a.file_name, a.content_type, a.size_bytes, a.sha256, a.created_at,
             u.id AS uid, u.email, u.display_name
      FROM attachments a JOIN users u ON u.id = a.uploaded_by
      """;

  private static AttachmentResponse row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
    return new AttachmentResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("expense_id", UUID.class),
        new UserSummary(
            rs.getObject("uid", UUID.class), rs.getString("email"), rs.getString("display_name")),
        rs.getString("file_name"),
        rs.getString("content_type"),
        rs.getLong("size_bytes"),
        rs.getString("sha256"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /** The allowed type whose signature the bytes actually start with, or null. */
  static String detect(byte[] b) {
    if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
    if (starts(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
    if (starts(b, 0, 'R', 'I', 'F', 'F') && starts(b, 8, 'W', 'E', 'B', 'P')) return "image/webp";
    if (starts(b, 0, '%', 'P', 'D', 'F', '-')) return "application/pdf";
    return null;
  }

  private static boolean starts(byte[] b, int offset, int... sig) {
    if (b.length < offset + sig.length) return false;
    for (int i = 0; i < sig.length; i++) if ((b[offset + i] & 0xFF) != sig[i]) return false;
    return true;
  }

  /** Display name only: no directory part, control characters or quoting characters. */
  static String cleanName(String raw) {
    String n = raw == null ? "" : raw;
    n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
    n = n.replaceAll("[\\p{Cntrl}\"<>:*?|]", "").strip();
    if (n.length() > 255) n = n.substring(0, 255);
    return n.isEmpty() ? "attachment" : n;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void requireExpense(UUID groupId, UUID expenseId) {
    boolean live =
        jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM expenses WHERE id=? AND group_id=? AND deleted_at IS NULL)")
            .params(expenseId, groupId)
            .query(Boolean.class)
            .single();
    if (!live) throw new NotFoundException("Expense not found");
  }
}
