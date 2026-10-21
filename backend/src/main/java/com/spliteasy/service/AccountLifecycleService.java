package com.spliteasy.service;

import com.spliteasy.dto.auth.RecoveryRequests.*;
import com.spliteasy.entity.User;
import com.spliteasy.exception.BadRequestException;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.util.Emails;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountLifecycleService {
  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final AccountMailService mail;
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();
  private static final String RESET = "PASSWORD_RESET";
  private static final String VERIFY = "EMAIL_VERIFICATION";
  private static final String CHANGE_EMAIL = "EMAIL_CHANGE";

  @Transactional
  public void requestReset(String email) {
    mail.requireConfigured();
    jdbc
        .query(
            "select id from users where email = ?",
            (rs, row) -> rs.getObject(1, UUID.class),
            Emails.normalize(email))
        .stream()
        .findFirst()
        .ifPresent(
            id -> {
              User current = lock(id);
              // Recheck after waiting for a concurrent email change.
              if (current.getEmail().equals(Emails.normalize(email)))
                issue(current, RESET, current.getEmail(), "password-reset");
            });
  }

  @Transactional
  public void resetPassword(PasswordReset request) {
    validatePassword(request.password());
    Challenge challenge = consume(request.token(), RESET);
    User user = lock(challenge.userId());
    if (!user.getEmail().equals(challenge.targetEmail())) throw invalidToken();
    user.setPasswordHash(passwords.encode(request.password()));
    invalidateCredentials(user);
  }

  @Transactional
  public void changePassword(UUID userId, PasswordChange request) {
    validatePassword(request.password());
    User user = lock(userId);
    requireCurrentPassword(user, request.currentPassword());
    user.setPasswordHash(passwords.encode(request.password()));
    invalidateCredentials(user);
  }

  @Transactional
  public void requestVerification(UUID userId) {
    mail.requireConfigured();
    User user = lock(userId);
    if (!user.isEmailVerified()) issue(user, VERIFY, user.getEmail(), "email-verification");
  }

  @Transactional
  public void verifyEmail(String token) {
    Challenge challenge = consume(token, VERIFY);
    User user = lock(challenge.userId());
    if (!user.getEmail().equals(challenge.targetEmail())) throw invalidToken();
    user.setEmailVerified(true);
  }

  @Transactional
  public void requestEmailChange(UUID userId, EmailChange request) {
    mail.requireConfigured();
    User user = lock(userId);
    requireCurrentPassword(user, request.currentPassword());
    if (user.getEmail().equals(request.email()))
      throw new BadRequestException("Choose a different email address");
    if (users.existsByEmail(request.email()))
      throw new com.spliteasy.exception.ConflictException("Email is already registered");
    issue(user, CHANGE_EMAIL, request.email(), "email-change");
  }

  @Transactional
  public void confirmEmailChange(String token) {
    Challenge challenge = consume(token, CHANGE_EMAIL);
    User user = lock(challenge.userId());
    if (user.getEmail().equals(challenge.targetEmail())) throw invalidToken();
    user.setEmail(challenge.targetEmail());
    user.setEmailVerified(true);
    invalidateCredentials(user);
  }

  private void requireCurrentPassword(User user, String currentPassword) {
    if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwords.matches(currentPassword, user.getPasswordHash()))
      throw new BadRequestException("Current password is incorrect");
  }

  private void validatePassword(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new BadRequestException("Password must be at most 72 UTF-8 bytes");
  }

  private User lock(UUID id) {
    return users.findLockedById(id).orElseThrow(this::invalidToken);
  }

  private void invalidateCredentials(User user) {
    user.setCredentialVersion(Math.incrementExact(user.getCredentialVersion()));
    jdbc.update("delete from account_challenges where user_id = ?", user.getId());
  }

  private void issue(User user, String purpose, String target, String action) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update(
        "delete from account_challenges where user_id = ? and purpose = ?", user.getId(), purpose);
    jdbc.update(
        "insert into account_challenges(token_hash,user_id,purpose,target_email,expires_at) values (?,?,?,?,?)",
        hash(token),
        user.getId(),
        purpose,
        target,
        Timestamp.from(Instant.now().plusSeconds(1800)));
    mail.send(target, action, token);
  }

  private Challenge consume(String token, String purpose) {
    if (!token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
    String hash = hash(token);
    List<Challenge> found = challenges(hash, purpose);
    if (found.isEmpty()) throw invalidToken();
    lock(found.getFirst().userId());
    found = challenges(hash, purpose);
    if (found.isEmpty() || !found.getFirst().expiresAt().isAfter(Instant.now()))
      throw invalidToken();
    jdbc.update("delete from account_challenges where token_hash = ?", hash);
    return found.getFirst();
  }

  private List<Challenge> challenges(String hash, String purpose) {
    return jdbc.query(
        "select user_id,target_email,expires_at from account_challenges where token_hash = ? and purpose = ?",
        (rs, row) ->
            new Challenge(
                rs.getObject("user_id", UUID.class),
                rs.getString("target_email"),
                rs.getTimestamp("expires_at").toInstant()),
        hash,
        purpose);
  }

  private String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private BadRequestException invalidToken() {
    return new BadRequestException("This link is invalid or expired. Request a new link.");
  }

  private record Challenge(UUID userId, String targetEmail, Instant expiresAt) {}
}
