package com.spliteasy.service;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.DeviceSession;
import com.spliteasy.dto.common.UserSummary;
import com.spliteasy.entity.User;
import com.spliteasy.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persisted device identity checked on every protected request; all writes lock its account. */
@Service
@RequiredArgsConstructor
public class AuthSessionService {
  private final JdbcTemplate jdbc;
  private final UserRepository users;

  private final JwtService jwt;
  private final java.security.SecureRandom random = new java.security.SecureRandom();
  private static final long IDLE_SECONDS = 7 * 86400L;

  // Internal issuance result: only response() is serialized. Never log this record.
  public record IssuedSession(
      AuthResponse response, String refreshCredential, long cookieSeconds) {}

  public IssuedSession create(User user, String userAgent) {
    // The caller owns the account lock (or has just inserted this account).
    jdbc.update(
        "delete from auth_sessions where user_id=? and (revoked_at is not null or expires_at <= CURRENT_TIMESTAMP or idle_expires_at <= CURRENT_TIMESTAMP or credential_version <> ?)",
        user.getId(),
        user.getCredentialVersion());
    jdbc.update(
        "delete from auth_sessions where id in (select id from auth_sessions where user_id=? order by created_at desc,id desc offset 19)",
        user.getId());
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    jdbc.update(
        "insert into auth_sessions(id,user_id,credential_version,device_label,created_at,refreshed_at,expires_at,idle_expires_at) values (?,?,?,?,?,?,?,?)",
        id,
        user.getId(),
        user.getCredentialVersion(),
        deviceLabel(userAgent),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(30 * 86400L)),
        Timestamp.from(now.plusSeconds(IDLE_SECONDS)));
    return issue(user, id, IDLE_SECONDS);
  }

  @Transactional
  public IssuedSession refresh(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    String hash = hash(token);
    var owner =
        jdbc.query(
            "select s.user_id from refresh_credentials c join auth_sessions s on s.id=c.session_id where c.token_hash=?",
            (rs, row) -> rs.getObject(1, UUID.class),
            hash);
    if (owner.isEmpty()) return null;
    User user = users.findLockedById(owner.getFirst()).orElse(null);
    if (user == null) return null;
    var credentials =
        jdbc.query(
            "select s.id,s.credential_version,s.expires_at,s.idle_expires_at,s.revoked_at,s.rotations,c.consumed_at from refresh_credentials c join auth_sessions s on s.id=c.session_id where c.token_hash=? and s.user_id=?",
            (rs, row) ->
                new Credential(
                    rs.getObject("id", UUID.class),
                    rs.getLong("credential_version"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("idle_expires_at").toInstant(),
                    rs.getTimestamp("revoked_at") != null,
                    rs.getInt("rotations"),
                    rs.getTimestamp("consumed_at") != null),
            hash,
            user.getId());
    if (credentials.isEmpty()) return null;
    var credential = credentials.getFirst();
    Instant now = Instant.now();
    if (credential.consumed() || credential.rotations() >= 2048) {
      jdbc.update(
          "update auth_sessions set revoked_at=? where id=? and revoked_at is null",
          Timestamp.from(now),
          credential.id());
      // Return normally so replay revocation commits even though the HTTP outcome is401.
      return null;
    }
    if (credential.revoked()
        || credential.version() != user.getCredentialVersion()
        || !credential.expiresAt().isAfter(now)
        || !credential.idleExpiresAt().isAfter(now)) return null;
    jdbc.update(
        "update refresh_credentials set consumed_at=? where token_hash=?",
        Timestamp.from(now),
        hash);
    long remaining =
        Math.max(1, java.time.Duration.between(now, credential.expiresAt()).toSeconds());
    long cookieSeconds = Math.min(IDLE_SECONDS, remaining);
    jdbc.update(
        "update auth_sessions set refreshed_at=?, idle_expires_at=?, rotations=rotations+1 where id=?",
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(cookieSeconds)),
        credential.id());
    return issue(user, credential.id(), cookieSeconds);
  }

  private IssuedSession issue(User user, UUID id, long cookieSeconds) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String refresh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(
        "insert into refresh_credentials(token_hash,session_id) values (?,?)", hash(refresh), id);
    return new IssuedSession(
        AuthResponse.bearer(
            jwt.issueToken(user, id), jwt.getExpirationSeconds(), UserSummary.from(user), id),
        refresh,
        cookieSeconds);
  }

  private String hash(String token) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private String deviceLabel(String agent) {
    if (agent == null) return "Browser or app";
    String browser =
        agent.contains("Edg/")
            ? "Edge"
            : agent.contains("Firefox/")
                ? "Firefox"
                : agent.contains("Chrome/")
                    ? "Chrome"
                    : agent.contains("Safari/") ? "Safari" : "Browser or app";
    String platform =
        agent.contains("Android")
            ? "Android"
            : agent.contains("iPhone") || agent.contains("iPad")
                ? "iOS"
                : agent.contains("Windows")
                    ? "Windows"
                    : agent.contains("Macintosh")
                        ? "macOS"
                        : agent.contains("Linux") ? "Linux" : "";
    return platform.isEmpty() ? browser : browser + " on " + platform;
  }

  private record Credential(
      UUID id,
      long version,
      Instant expiresAt,
      Instant idleExpiresAt,
      boolean revoked,
      int rotations,
      boolean consumed) {}

  public boolean active(UUID userId, UUID sessionId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists(select 1 from auth_sessions where id=? and user_id=? and revoked_at is null and expires_at > CURRENT_TIMESTAMP and idle_expires_at > CURRENT_TIMESTAMP)",
            Boolean.class,
            sessionId,
            userId));
  }

  @Transactional(readOnly = true)
  public List<DeviceSession> list(UUID userId, UUID currentId) {
    return jdbc.query(
        "select id,device_label,created_at,refreshed_at,expires_at from auth_sessions where user_id=? and revoked_at is null and expires_at > CURRENT_TIMESTAMP and idle_expires_at > CURRENT_TIMESTAMP order by created_at desc,id",
        (rs, row) ->
            new DeviceSession(
                rs.getObject("id", UUID.class),
                rs.getString("device_label"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("refreshed_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                currentId.equals(rs.getObject("id", UUID.class))),
        userId);
  }

  @Transactional
  public void revokeAll(UUID userId) {
    users.findLockedById(userId).orElseThrow();
    jdbc.update(
        "update auth_sessions set revoked_at = CURRENT_TIMESTAMP where user_id=? and revoked_at is null",
        userId);
  }

  @Transactional
  public void revoke(UUID userId, UUID sessionId) {
    users.findLockedById(userId).orElseThrow();
    jdbc.update(
        "update auth_sessions set revoked_at = CURRENT_TIMESTAMP where id=? and user_id=? and revoked_at is null",
        sessionId,
        userId);
  }
}
