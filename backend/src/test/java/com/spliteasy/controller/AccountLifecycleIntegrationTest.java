package com.spliteasy.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.LoginRequest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@SpringBootTest(
    properties = {
      "app.mail.enabled=true", "app.mail.from=no-reply@example.test",
      "app.mail.frontend-url=http://localhost:4200", "app.mail.allow-insecure-local=true"
    })
@Import(AccountLifecycleIntegrationTest.MailConfiguration.class)
class AccountLifecycleIntegrationTest extends AbstractIntegrationTest {
  @Autowired Mailbox mailbox;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired javax.sql.DataSource dataSource;

  @TestConfiguration
  static class MailConfiguration {
    @Bean
    Mailbox mailbox() {
      return new Mailbox();
    }
  }

  static class Mailbox extends JavaMailSenderImpl {
    final Map<String, SimpleMailMessage> messages = new ConcurrentHashMap<>();
    final java.util.Set<String> failingEmails = ConcurrentHashMap.newKeySet();

    Mailbox() {
      setHost("127.0.0.1");
    }

    @Override
    public void send(SimpleMailMessage message) {
      messages.put(message.getTo()[0], new SimpleMailMessage(message));
      if (failingEmails.contains(message.getTo()[0]))
        throw new org.springframework.mail.MailSendException("Controlled mailbox outage");
    }

    String token(String email) {
      String text = messages.get(email).getText();
      assertNotNull(text);
      return text.substring(text.indexOf("&token=") + 7).trim();
    }
  }

  @Test
  void forgottenPasswordUsesOnlyMailboxChallengeAndRevokesOldAccessTokens() throws Exception {
    String email = "forgotten@example.com";
    var auth = register(email, "password123", "Forgotten");
    for (String target : new String[] {" UNKNOWN@example.com ", " FORGOTTEN@example.com "}) {
      mockMvc
          .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", target)))
          .andExpect(status().isAccepted())
          .andExpect(content().string(""));
    }
    assertFalse(mailbox.messages.containsKey("unknown@example.com"));
    String token = mailbox.token(email);
    assertEquals(43, token.length());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets", Map.of("token", token, "password", "newPassword123")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, "password123")))
        .andExpect(status().isUnauthorized());
    var fresh = login(email, "newPassword123");
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(auth.user().id().toString()));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets", Map.of("token", token, "password", "anotherPassword")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void verifiedReplacementEmailRemainsPendingUntilMailboxConfirmation() throws Exception {
    String oldEmail = "old-email@example.com";
    String newEmail = "new-email@example.com";
    var auth = register(oldEmail, "password123", "Email owner");
    String authorization = bearer(auth.accessToken());
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailVerified").value(false));
    mockMvc
        .perform(
            jsonPost("/api/me/email-verification-requests", Map.of())
                .header("Authorization", authorization))
        .andExpect(status().isAccepted());
    String verification = mailbox.token(oldEmail);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", verification, "password", "wrongPurposePassword")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(jsonPost("/api/auth/email-verifications", Map.of("token", verification)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.emailVerified").value(true));
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/email-change-requests",
                    Map.of("currentPassword", "password123", "email", " NEW-email@example.com "))
                .header("Authorization", authorization))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(newEmail, "password123")))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(oldEmail, "password123")))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(oldEmail));
    String confirmation = mailbox.token(newEmail);
    mockMvc
        .perform(jsonPost("/api/auth/email-change-confirmations", Map.of("token", confirmation)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(oldEmail, "password123")))
        .andExpect(status().isUnauthorized());
    var fresh = login(newEmail, "password123");
    assertEquals(auth.user().id(), fresh.user().id());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(newEmail))
        .andExpect(jsonPath("$.emailVerified").value(true));
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(jsonPost("/api/auth/email-change-confirmations", Map.of("token", confirmation)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authenticatedPasswordChangeRequiresReauthenticationAndInvalidatesPendingLinks()
      throws Exception {
    String email = "password-change@example.com";
    var auth = register(email, "password123", "Password owner");
    String authorization = bearer(auth.accessToken());
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String pendingReset = mailbox.token(email);
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    Map.of("currentPassword", "wrongPassword", "password", "changedPassword123"))
                .header("Authorization", authorization))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    Map.of("currentPassword", "password123", "password", "changedPassword123"))
                .header("Authorization", authorization))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", authorization))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, "password123")))
        .andExpect(status().isUnauthorized());
    var fresh = login(email, "changedPassword123");
    assertEquals(auth.user().id(), fresh.user().id());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", pendingReset, "password", "oldChallengeMustFail")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            jsonPost(
                "/api/me/password",
                Map.of("currentPassword", "changedPassword123", "password", "missingAuthMustFail")))
        .andExpect(status().isUnauthorized());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"a", "é", "😀"})
  void passwordLifecycleEnforcesBcryptBytesWithoutTruncatingCredentials(String character)
      throws Exception {
    int width = character.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    String exact = character.repeat(72 / width);
    String oversized = exact + character;
    String email = "byte-boundary-" + character.codePointAt(0) + "@example.com";
    register(email, "password123", "Boundary");
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String token = mailbox.token(email);
    mockMvc
        .perform(
            jsonPost("/api/auth/password-resets", Map.of("token", token, "password", oversized)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(jsonPost("/api/auth/password-resets", Map.of("token", token, "password", exact)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, oversized)))
        .andExpect(status().isUnauthorized());
    var fresh = login(email, exact);
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    Map.of("currentPassword", oversized, "password", "mustNotTruncate"))
                .header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            jsonPost("/api/me/password", Map.of("currentPassword", exact, "password", oversized))
                .header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    Map.of("currentPassword", exact, "password", "boundaryAccepted"))
                .header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, "boundaryAccepted")))
        .andExpect(status().isOk());
  }

  @Test
  void actualSmtpFailureKeepsRecoveryGenericAndRollsBackUndeliveredChallenge() throws Exception {
    String email = "smtp-outage@example.com";
    register(email, "password123", "Mailbox outage");
    mailbox.failingEmails.add(email);
    try {
      for (String target : new String[] {email, "smtp-unknown@example.com"}) {
        mockMvc
            .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", target)))
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));
      }
      String failedToken = mailbox.token(email);
      mockMvc
          .perform(
              jsonPost(
                  "/api/auth/password-resets",
                  Map.of("token", failedToken, "password", "undeliveredMustFail")))
          .andExpect(status().isBadRequest());
    } finally {
      mailbox.failingEmails.remove(email);
    }
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", mailbox.token(email), "password", "mailRecovered123")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, "mailRecovered123")))
        .andExpect(status().isOk());
  }

  @Test
  void replacedExpiredAndSimultaneouslyConsumedLinksCannotOverwriteAnAcceptedReset()
      throws Exception {
    String email = "challenge-lifecycle@example.com";
    var auth = register(email, "password123", "Challenges");
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String replaced = mailbox.token(email);
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String expired = mailbox.token(email);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", replaced, "password", "replacedMustFail")))
        .andExpect(status().isBadRequest());
    // Database sets up an expired fixture; all outcomes are asserted through public HTTP.
    jdbc.update(
        "update account_challenges set expires_at = now() - interval '1 hour' where user_id = ?",
        auth.user().id());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", expired, "password", "expiredMustFail")))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String token = mailbox.token(email);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          pool.submit(
              () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        jsonPost(
                            "/api/auth/password-resets",
                            Map.of("token", token, "password", "firstRacePassword")))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var second =
          pool.submit(
              () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        jsonPost(
                            "/api/auth/password-resets",
                            Map.of("token", token, "password", "secondRacePassword")))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      int firstStatus = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      int secondStatus = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(java.util.Set.of(204, 400), java.util.Set.of(firstStatus, secondStatus));
      String winner = firstStatus == 204 ? "firstRacePassword" : "secondRacePassword";
      String loser = firstStatus == 204 ? "secondRacePassword" : "firstRacePassword";
      mockMvc
          .perform(jsonPost("/api/auth/login", new LoginRequest(email, winner)))
          .andExpect(status().isOk());
      mockMvc
          .perform(jsonPost("/api/auth/login", new LoginRequest(email, loser)))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void simultaneousReplacementClaimsHaveOneWinnerWithoutChangingTheLosingAccount()
      throws Exception {
    var first = register("email-race-first@example.com", "firstPassword123", "First owner");
    var second = register("email-race-second@example.com", "secondPassword123", "Second owner");
    String target = "email-race-target@example.com";
    // Neither old mailbox is verified; reauthentication plus the new mailbox is sufficient.
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/email-change-requests",
                    Map.of("currentPassword", "firstPassword123", "email", target))
                .header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isAccepted());
    String firstToken = mailbox.token(target);
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/email-change-requests",
                    Map.of("currentPassword", "secondPassword123", "email", target.toUpperCase()))
                .header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isAccepted());
    String secondToken = mailbox.token(target);
    var barrier = new java.util.concurrent.CyclicBarrier(2);
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var firstClaim =
          pool.submit(
              () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        jsonPost(
                            "/api/auth/email-change-confirmations", Map.of("token", firstToken)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      var secondClaim =
          pool.submit(
              () -> {
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return mockMvc
                    .perform(
                        jsonPost(
                            "/api/auth/email-change-confirmations", Map.of("token", secondToken)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
              });
      int firstStatus = firstClaim.get(10, java.util.concurrent.TimeUnit.SECONDS);
      int secondStatus = secondClaim.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(java.util.Set.of(204, 409), java.util.Set.of(firstStatus, secondStatus));
      var winner = firstStatus == 204 ? first : second;
      var loser = firstStatus == 204 ? second : first;
      String winnerPassword = firstStatus == 204 ? "firstPassword123" : "secondPassword123";
      String loserPassword = firstStatus == 204 ? "secondPassword123" : "firstPassword123";
      assertEquals(winner.user().id(), login(target, winnerPassword).user().id());
      assertEquals(loser.user().id(), login(loser.user().email(), loserPassword).user().id());
      mockMvc
          .perform(get("/api/me").header("Authorization", bearer(loser.accessToken())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.email").value(loser.user().email()))
          .andExpect(jsonPath("$.emailVerified").value(false));
      mockMvc
          .perform(get("/api/me").header("Authorization", bearer(winner.accessToken())))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void resetWaitingBehindConfirmedEmailChangeCannotSendAUsableLinkToFormerAddress()
      throws Exception {
    String oldEmail = "old-race-mailbox@example.com";
    String newEmail = "new-race-mailbox@example.com";
    var auth = register(oldEmail, "password123", "Mailbox race");
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", oldEmail)))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/email-change-requests",
                    Map.of("currentPassword", "password123", "email", newEmail))
                .header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isAccepted());
    String emailToken = mailbox.token(newEmail);
    // This connection only coordinates lock order. Both competing mutations and outcomes use HTTP.
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("select id from users where id = ? for update")) {
        statement.setObject(1, auth.user().id());
        statement.executeQuery().close();
      }
      var confirmation =
          pool.submit(
              () ->
                  mockMvc
                      .perform(
                          jsonPost(
                              "/api/auth/email-change-confirmations", Map.of("token", emailToken)))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitWaitingWriters(1);
      var reset =
          pool.submit(
              () ->
                  mockMvc
                      .perform(
                          jsonPost("/api/auth/password-reset-requests", Map.of("email", oldEmail)))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitWaitingWriters(2);
      connection.commit();
      assertEquals(204, confirmation.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(202, reset.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    // The last link visible in the old mailbox (old or newly attempted) cannot recover the account.
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", mailbox.token(oldEmail), "password", "formerMailboxMustFail")))
        .andExpect(status().isBadRequest());
    var fresh = login(newEmail, "password123");
    assertEquals(auth.user().id(), fresh.user().id());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(newEmail));
  }

  @Test
  void lifecycleLimitsCountUnknownAccountsAndBadChallengesWithoutRevokingExistingAccess()
      throws Exception {
    var auth = register("lifecycle-limits@example.com", "password123", "Limited owner");
    // Account recovery budget follows normalized email across source IPs.
    for (int i = 0; i < 5; i++) {
      int attempt = i;
      mockMvc
          .perform(
              jsonPost(
                      "/api/auth/password-reset-requests",
                      Map.of("email", "limit-unknown@example.com"))
                  .with(
                      r -> {
                        r.setRemoteAddr("203.0.113." + (100 + attempt));
                        return r;
                      }))
          .andExpect(status().isAccepted());
    }
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-reset-requests",
                Map.of("email", " LIMIT-UNKNOWN@example.com ")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    for (int i = 0; i < 20; i++) {
      mockMvc
          .perform(
              jsonPost(
                  "/api/auth/password-reset-requests",
                  Map.of("email", "ip-budget-" + i + "@example.com")))
          .andExpect(status().isAccepted());
    }
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-reset-requests",
                Map.of("email", "ip-budget-exhausted@example.com")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    for (int i = 0; i < 30; i++) {
      mockMvc
          .perform(
              jsonPost(
                  "/api/auth/password-resets",
                  Map.of("token", "invalid-token", "password", "changedPassword123")))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(jsonPost("/api/auth/email-verifications", Map.of("token", "invalid-token")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              jsonPost(
                      "/api/me/password",
                      Map.of("currentPassword", "wrongPassword", "password", "changedPassword123"))
                  .header("Authorization", bearer(auth.accessToken())))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/email-change-requests",
                    Map.of(
                        "currentPassword", "password123", "email", "must-not-change@example.com"))
                .header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("lifecycle-limits@example.com"));
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/login", new LoginRequest("lifecycle-limits@example.com", "password123")))
        .andExpect(status().isOk());
  }

  @Test
  void insecureLocalExceptionNeverAllowsRemoteSmtpWithAnHttpFrontend() throws Exception {
    register("remote-http@example.com", "password123", "Origin owner");
    mailbox.setHost("smtp.example.test");
    mailbox.setUsername("owned-test-user");
    mailbox.setPassword("owned-test-password");
    var properties = mailbox.getJavaMailProperties();
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.smtp.starttls.required", "true");
    properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
    for (String timeout : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      properties.setProperty("mail.smtp." + timeout, "5000");
    }
    try {
      for (String email :
          new String[] {"remote-http@example.com", "unknown-remote-http@example.com"}) {
        mockMvc
            .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
            .andExpect(status().isServiceUnavailable());
      }
    } finally {
      mailbox.setHost("127.0.0.1");
      mailbox.setUsername(null);
      mailbox.setPassword(null);
      properties.clear();
    }
  }

  private void awaitWaitingWriters(int count) throws Exception {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
    while (System.nanoTime() < deadline) {
      int waiting =
          jdbc.queryForObject(
              "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock' and query like '%users%'",
              Integer.class);
      if (waiting >= count) return;
      Thread.sleep(10);
    }
    fail("Expected competing HTTP writers to wait for the coordination row lock");
  }
}
