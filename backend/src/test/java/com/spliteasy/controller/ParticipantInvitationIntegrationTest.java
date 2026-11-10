package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@org.springframework.boot.test.context.SpringBootTest(
    properties = {
      "app.mail.enabled=true",
      "app.mail.from=no-reply@example.test",
      "app.mail.frontend-url=http://localhost:4200",
      "app.mail.allow-insecure-local=true"
    })
@org.springframework.context.annotation.Import(
    ParticipantInvitationIntegrationTest.MailConfiguration.class)
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class ParticipantInvitationIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired Mailbox mailbox;
  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.spliteasy.repository.GroupRepository groupLocks;

  @org.springframework.boot.test.context.TestConfiguration
  static class MailConfiguration {
    @org.springframework.context.annotation.Bean
    Mailbox mailbox() {
      return new Mailbox();
    }
  }

  static class Mailbox extends org.springframework.mail.javamail.JavaMailSenderImpl {
    final java.util.Map<String, org.springframework.mail.SimpleMailMessage> messages =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<String, Integer> counts = new java.util.concurrent.ConcurrentHashMap<>();

    Mailbox() {
      setHost("127.0.0.1");
    }

    @Override
    public void send(org.springframework.mail.SimpleMailMessage message) {
      messages.put(message.getTo()[0], new org.springframework.mail.SimpleMailMessage(message));
      counts.merge(message.getTo()[0], 1, Integer::sum);
    }

    String token(String email) {
      String text = messages.get(email).getText();
      return text.substring(text.indexOf("&token=") + 7).trim();
    }
  }

  private AuthResponse account(String name) throws Exception {
    return register(UUID.randomUUID() + "@example.test", "password123", name);
  }

  private JsonNode send(AuthResponse actor, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(actor.accessToken()))
                    .header("X-Ledger-Version", "2")
                    .header("X-Participant-Version", "1"))
            .andExpect(status().is(expected))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  @Test
  void newAccountsHaveStableFinancialParticipantsSeparateFromAccessMembership() throws Exception {
    var alice = account("Alice");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Participants")), 201);
    assertThat(group.path("members").get(0).path("id").asText())
        .isEqualTo(alice.user().id().toString());
    assertThat(group.path("participants").size()).isEqualTo(1);
    assertThat(group.path("participants").get(0).path("canonicalParticipantId").asText())
        .isEqualTo(alice.user().id().toString());
    assertThat(group.path("viewerParticipantId").asText()).isEqualTo(alice.user().id().toString());
    var dashboard = send(alice, get("/api/dashboard"), 200);
    assertThat(dashboard.path("viewerParticipantId").asText())
        .isEqualTo(alice.user().id().toString());
    assertThat(dashboard.path("ownedParticipantIds").toString())
        .contains(alice.user().id().toString());
  }

  @Test
  void claimingAnExistingAccountsSecondIdentityCombinesOnceWithoutChangingOriginalExpense()
      throws Exception {
    var alice = account("Alice");
    var creditor = account("Creditor");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Aliases")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    UUID groupId = UUID.fromString(group.path("id").asText());
    send(alice, jsonPost(path + "/members", Map.of("email", creditor.user().email())), 200);
    UUID guest = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO participants(id,display_name,created_at,scope_group_id) VALUES(?,'Guest',CURRENT_TIMESTAMP,?)",
        guest,
        groupId);
    jdbc.update(
        "INSERT INTO group_participants VALUES(?,?,true,CURRENT_TIMESTAMP)", groupId, guest);
    var expense =
        send(
            alice,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Dinner",
                    "currency",
                    "USD",
                    "amountMinor",
                    1200,
                    "paidByUserId",
                    creditor.user().id(),
                    "participantUserIds",
                    java.util.List.of(alice.user().id(), guest, creditor.user().id()))),
            201);
    jdbc.update(
        "INSERT INTO participant_account_links(participant_id,user_id,linked_at,link_kind) VALUES(?,?,CURRENT_TIMESTAMP,'VERIFIED_EMAIL_CLAIM')",
        guest,
        alice.user().id());
    var balances = send(alice, get(path + "/balances"), 200);
    assertThat(balances.path("balances").size()).isEqualTo(2);
    var mine = send(alice, get(path + "/balances/mine"), 200);
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).path("netMinor").asLong()).isEqualTo(-800);
    var list = send(alice, get(path + "/expenses"), 200);
    assertThat(list.get(0).path("viewerDeltaMinor").asLong()).isEqualTo(-800);
    var detail = send(alice, get(path + "/expenses/" + expense.path("id").asText()), 200);
    assertThat(detail.path("participantUserIds")).isEqualTo(expense.path("participantUserIds"));
    assertThat(detail.path("version")).isEqualTo(expense.path("version"));
    assertThat(detail.path("amountMinor").asLong()).isEqualTo(1200);
    for (var share : detail.path("participants"))
      if (share.path("user").path("id").asText().equals(guest.toString()))
        assertThat(share.path("user").path("displayName").asText()).isEqualTo("Guest");
    var newDefault =
        send(
            alice,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "After claim",
                    "amountCents",
                    600,
                    "paidByUserId",
                    creditor.user().id())),
            201);
    assertThat(newDefault.path("participants").size()).isEqualTo(2);
    send(
        alice,
        jsonPost(
            path + "/expenses",
            Map.of(
                "description",
                "Duplicate aliases",
                "amountCents",
                600,
                "paidByUserId",
                creditor.user().id(),
                "participantUserIds",
                java.util.List.of(alice.user().id(), guest))),
        409);
    send(
        alice,
        jsonPost(
            path + "/payments",
            Map.of("payerUserId", guest, "payeeUserId", alice.user().id(), "amountCents", 100)),
        400);
    var replacement = new java.util.HashMap<String, Object>();
    replacement.put("description", "Historical aliases");
    replacement.put("amountCents", 1200);
    replacement.put("paidByUserId", creditor.user().id());
    replacement.put(
        "participantUserIds", java.util.List.of(alice.user().id(), guest, creditor.user().id()));
    replacement.put("expectedVersion", expense.path("version").asLong());
    var updated =
        send(
            alice,
            put(path + "/expenses/" + expense.path("id").asText())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json(replacement)),
            200);
    assertThat(updated.path("participantUserIds")).isEqualTo(expense.path("participantUserIds"));
  }

  @Test
  void legacyClientsFailBeforeNewWritesButRecoverOriginalRepresentableReceipt() throws Exception {
    var alice = account("Alice");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Compatibility")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    UUID operation = UUID.randomUUID();
    var request =
        Map.of(
            "description",
            "Original",
            "amountCents",
            300,
            "paidByUserId",
            alice.user().id(),
            "operationId",
            operation);
    var original = send(alice, jsonPost(path + "/expenses", request), 201);
    UUID guest = UUID.randomUUID();
    UUID groupId = UUID.fromString(group.path("id").asText());
    jdbc.update(
        "INSERT INTO participants(id,display_name,created_at,scope_group_id) VALUES(?,'Guest',CURRENT_TIMESTAMP,?)",
        guest,
        groupId);
    jdbc.update(
        "INSERT INTO group_participants VALUES(?,?,true,CURRENT_TIMESTAMP)", groupId, guest);
    for (String suffix :
        java.util.List.of(
            "",
            "/expenses",
            "/expenses/deleted",
            "/expenses/" + original.path("id").asText() + "/history",
            "/balances",
            "/balances/mine",
            "/balances/obligations",
            "/balances/current-obligations",
            "/debt-simplification",
            "/payments")) {
      mockMvc
          .perform(get(path + suffix).header("Authorization", bearer(alice.accessToken())))
          .andExpect(status().isConflict());
    }
    mockMvc
        .perform(
            jsonPost(
                    path + "/expenses",
                    Map.of(
                        "description",
                        "New",
                        "amountCents",
                        300,
                        "paidByUserId",
                        alice.user().id()))
                .header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    var replay =
        mockMvc
            .perform(
                jsonPost(path + "/expenses", request)
                    .header("Authorization", bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()))
        .isEqualTo(original);
    mockMvc
        .perform(
            jsonPost(
                    path + "/expenses",
                    Map.of(
                        "description",
                        "Original",
                        "amountCents",
                        300,
                        "paidByUserId",
                        guest,
                        "operationId",
                        operation))
                .header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    assertThat(send(alice, get(path + "/expenses"), 200).size()).isEqualTo(1);
  }

  @Test
  void preSignupGuestSharesSurviveSettlementsAndSettledRosterRemoval() throws Exception {
    var alice = account("Alice");
    var stranger = account("Stranger");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Guests")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    UUID operation = UUID.randomUUID();
    var request = Map.of("displayName", "Before signup", "operationId", operation);
    var guest = send(alice, jsonPost(path + "/participants", request), 201);
    assertThat(guest.path("accountId").isNull()).isTrue();
    assertThat(guest.path("email").isNull()).isTrue();
    assertThat(send(alice, jsonPost(path + "/participants", request), 201)).isEqualTo(guest);
    var detail = send(alice, get(path), 200);
    assertThat(detail.path("members").size()).isEqualTo(1);
    assertThat(detail.path("participants").size()).isEqualTo(2);
    var expense =
        send(
            alice,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Guest dinner",
                    "currency",
                    "KWD",
                    "amountMinor",
                    1234,
                    "paidByUserId",
                    alice.user().id())),
            201);
    assertThat(expense.path("participants").size()).isEqualTo(2);
    assertThat(send(alice, get(path + "/balances/mine"), 200).get(0).path("netMinor").asLong())
        .isEqualTo(617);
    send(stranger, get(path), 403);
    send(alice, delete(path + "/participants/" + guest.path("id").asText()), 409);
    send(
        alice,
        jsonPost(
            path + "/payments",
            Map.of(
                "payerUserId",
                guest.path("id").asText(),
                "payeeUserId",
                alice.user().id(),
                "currency",
                "KWD",
                "amountMinor",
                617)),
        201);
    send(alice, delete(path + "/participants/" + guest.path("id").asText()), 204);
    assertThat(send(alice, get(path), 200).path("participants").size()).isEqualTo(1);
    send(
        alice,
        delete(path + "/expenses/" + expense.path("id").asText())
            .param("expectedVersion", expense.path("version").asText()),
        409);
  }

  @Test
  void inactiveRosterWithoutFinancialFactsDoesNotInventBalanceRows() throws Exception {
    var alice = account("Alice");
    var former = account("Former");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Retained roster")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    send(alice, jsonPost(path + "/members", Map.of("email", former.user().email())), 200);
    send(alice, delete(path + "/members/" + former.user().id()), 204);
    assertThat(send(alice, get(path + "/balances"), 200).path("balances").size()).isEqualTo(1);
  }

  @Test
  void verifiedExistingAccountExplicitlyClaimsGuestWithoutDuplicatingMembershipOrMoney()
      throws Exception {
    var alice = account("Alice");
    var recipient = account("Recipient");
    var stranger = account("Stranger");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Claims")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    send(alice, jsonPost(path + "/members", Map.of("email", recipient.user().email())), 200);
    var guest =
        send(alice, jsonPost(path + "/participants", Map.of("displayName", "Old guest")), 201);
    var expense =
        send(
            alice,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Before claim",
                    "amountCents",
                    1200,
                    "paidByUserId",
                    alice.user().id())),
            201);
    UUID operation = UUID.randomUUID();
    var request =
        Map.of(
            "email",
            recipient.user().email(),
            "participantId",
            guest.path("id").asText(),
            "operationId",
            operation);
    var invite = send(alice, jsonPost(path + "/invitations/email", request), 201);
    String token = mailbox.token(recipient.user().email());
    assertThat(send(alice, jsonPost(path + "/invitations/email", request), 201)).isEqualTo(invite);
    assertThat(mailbox.counts.get(recipient.user().email())).isEqualTo(1);
    send(stranger, jsonPost("/api/invitations/preview", Map.of("token", token)), 400);
    send(recipient, jsonPost("/api/invitations/preview", Map.of("token", token)), 400);
    send(recipient, jsonPost("/api/me/email-verification-requests", Map.of()), 202);
    String verification = mailbox.token(recipient.user().email());
    mockMvc
        .perform(jsonPost("/api/auth/email-verifications", Map.of("token", verification)))
        .andExpect(status().isNoContent());
    var preview =
        send(recipient, jsonPost("/api/invitations/preview", Map.of("token", token)), 200);
    assertThat(preview.path("requiresOwnershipConfirmation").asBoolean()).isTrue();
    assertThat(preview.path("balances").get(0).path("existingNetMinor").asLong()).isEqualTo(-400);
    assertThat(preview.path("balances").get(0).path("participantNetMinor").asLong())
        .isEqualTo(-400);
    assertThat(preview.path("balances").get(0).path("resultingNetMinor").asLong()).isEqualTo(-800);
    send(recipient, jsonPost("/api/invitations/accept", Map.of("token", token)), 400);
    var accepted =
        send(
            recipient,
            jsonPost("/api/invitations/accept", Map.of("token", token, "confirmOwnership", true)),
            200);
    assertThat(accepted.path("claimed").asBoolean()).isTrue();
    send(
        recipient,
        jsonPost("/api/invitations/accept", Map.of("token", token, "confirmOwnership", true)),
        400);
    var joined = send(recipient, get(path), 200);
    assertThat(joined.path("members").size()).isEqualTo(2);
    assertThat(joined.path("participants").size()).isEqualTo(2);
    assertThat(joined.path("ownedParticipantIds").toString()).contains(guest.path("id").asText());
    assertThat(send(recipient, get(path + "/balances/mine"), 200).get(0).path("netMinor").asLong())
        .isEqualTo(-800);
    assertThat(
            send(recipient, get(path + "/expenses/" + expense.path("id").asText()), 200)
                .path("participantUserIds"))
        .isEqualTo(expense.path("participantUserIds"));
  }

  @Test
  void explicitResendToAnotherRecipientRevokesThePreviousGuestInvitation() throws Exception {
    var owner = account("Owner");
    var first = account("First");
    var second = account("Second");
    jdbc.update(
        "UPDATE users SET email_verified=true WHERE id IN (?,?)",
        first.user().id(),
        second.user().id());
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "Resend")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    var guest = send(owner, jsonPost(path + "/participants", Map.of("displayName", "Guest")), 201);
    var original =
        send(
            owner,
            jsonPost(
                path + "/invitations/email",
                Map.of("email", first.user().email(), "participantId", guest.path("id").asText())),
            201);
    String oldToken = mailbox.token(first.user().email());
    send(first, jsonPost("/api/invitations/preview", Map.of("token", oldToken)), 200);
    send(
        owner,
        jsonPost(
            path + "/invitations/email",
            Map.of(
                "email",
                second.user().email(),
                "participantId",
                guest.path("id").asText(),
                "resend",
                true)),
        201);
    send(first, jsonPost("/api/invitations/preview", Map.of("token", oldToken)), 400);
    var status = send(owner, get(path + "/invitations"), 200);
    for (var row : status)
      if (row.path("id").equals(original.path("id")))
        assertThat(row.path("status").asText()).isEqualTo("REVOKED");
    send(
        second,
        jsonPost(
            "/api/invitations/accept",
            Map.of("token", mailbox.token(second.user().email()), "confirmOwnership", true)),
        200);
  }

  @Test
  void aClaimWaitingForTheGroupCannotOutliveDeviceRevocation() throws Exception {
    var owner = account("Owner");
    var recipient = account("Recipient");
    jdbc.update("UPDATE users SET email_verified=true WHERE id=?", recipient.user().id());
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "Revoked during wait")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    UUID groupId = UUID.fromString(group.path("id").asText());
    var guest = send(owner, jsonPost(path + "/participants", Map.of("displayName", "Guest")), 201);
    send(
        owner,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", recipient.user().email(), "participantId", guest.path("id").asText())),
        201);
    String token = mailbox.token(recipient.user().email());
    try (var blocker = dataSource.getConnection();
        var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("SELECT id FROM groups WHERE id=? FOR UPDATE")) {
        statement.setObject(1, groupId);
        statement.executeQuery().close();
      }
      int pid;
      try (var statement = blocker.createStatement();
          var result = statement.executeQuery("SELECT pg_backend_pid()")) {
        result.next();
        pid = result.getInt(1);
      }
      var claim =
          requests.submit(
              () ->
                  mockMvc
                      .perform(
                          jsonPost(
                                  "/api/invitations/accept",
                                  Map.of("token", token, "confirmOwnership", true))
                              .header("Authorization", bearer(recipient.accessToken()))
                              .header("X-Participant-Version", "1"))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      boolean blocked = false;
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        blocked =
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                    Boolean.class,
                    pid));
        if (blocked) break;
        Thread.yield();
      }
      try {
        assertThat(blocked).isTrue();
        send(recipient, jsonPost("/api/me/sessions/logout-all", Map.of()), 204);
      } finally {
        blocker.commit();
      }
      assertThat(claim.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(401);
    }
    var unchanged = send(owner, get(path), 200);
    assertThat(unchanged.path("members").size()).isEqualTo(1);
    for (var participant : unchanged.path("participants"))
      if (participant.path("id").equals(guest.path("id")))
        assertThat(participant.path("accountId").isNull()).isTrue();
  }

  @Test
  void concurrentVerifiedClaimsHaveOneWinnerAndOneFinancialProjection() throws Exception {
    var owner = account("Owner");
    var recipient = account("Recipient");
    jdbc.update("UPDATE users SET email_verified=true WHERE id=?", recipient.user().id());
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "One winner")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    var guest = send(owner, jsonPost(path + "/participants", Map.of("displayName", "Guest")), 201);
    var expense =
        send(
            owner,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Before race",
                    "amountCents",
                    1000,
                    "paidByUserId",
                    owner.user().id())),
            201);
    send(
        owner,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", recipient.user().email(), "participantId", guest.path("id").asText())),
        201);
    String token = mailbox.token(recipient.user().email());
    var start = new java.util.concurrent.CyclicBarrier(2);
    try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      java.util.concurrent.Callable<Integer> claim =
          () -> {
            start.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return mockMvc
                .perform(
                    jsonPost(
                            "/api/invitations/accept",
                            Map.of("token", token, "confirmOwnership", true))
                        .header("Authorization", bearer(recipient.accessToken()))
                        .header("X-Participant-Version", "1"))
                .andReturn()
                .getResponse()
                .getStatus();
          };
      var first = requests.submit(claim);
      var second = requests.submit(claim);
      assertThat(
              java.util.List.of(
                  first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                  second.get(20, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 400);
    }
    assertThat(send(recipient, get(path), 200).path("members").size()).isEqualTo(2);
    assertThat(send(recipient, get(path + "/expenses"), 200).size()).isEqualTo(1);
    assertThat(send(recipient, get(path + "/balances/mine"), 200).get(0).path("netMinor").asLong())
        .isEqualTo(-500);
    assertThat(
            send(recipient, get(path + "/expenses/" + expense.path("id").asText()), 200)
                .path("participantUserIds"))
        .isEqualTo(expense.path("participantUserIds"));
  }

  @Test
  void unclaimedGuestReactivationRestoresOriginalHistoryWithoutRevivingOldInvitations()
      throws Exception {
    var owner = account("Owner");
    var recipient = account("Recipient");
    jdbc.update("UPDATE users SET email_verified=true WHERE id=?", recipient.user().id());
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "Guest recovery")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    var guest =
        send(owner, jsonPost(path + "/participants", Map.of("displayName", "Original guest")), 201);
    var expense =
        send(
            owner,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Original KWD",
                    "currency",
                    "KWD",
                    "amountMinor",
                    1234,
                    "paidByUserId",
                    owner.user().id())),
            201);
    send(
        owner,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", recipient.user().email(), "participantId", guest.path("id").asText())),
        201);
    String oldToken = mailbox.token(recipient.user().email());
    String expensePath = path + "/expenses/" + expense.path("id").asText();
    send(
        owner, delete(expensePath).param("expectedVersion", expense.path("version").asText()), 204);
    long deletedVersion =
        send(owner, get(path + "/expenses/deleted"), 200)
            .get(0)
            .path("expense")
            .path("version")
            .asLong();
    send(owner, delete(path + "/participants/" + guest.path("id").asText()), 204);
    send(recipient, jsonPost("/api/invitations/preview", Map.of("token", oldToken)), 400);
    var removed = send(owner, get(path), 200);
    assertThat(removed.path("inactiveGuests").size()).isEqualTo(1);
    assertThat(removed.path("inactiveGuests").get(0).path("id")).isEqualTo(guest.path("id"));
    send(owner, jsonPost(expensePath + "/restore", Map.of("expectedVersion", deletedVersion)), 409);
    var reactivated =
        send(
            owner,
            jsonPost(path + "/participants/" + guest.path("id").asText() + "/reactivate", Map.of()),
            200);
    assertThat(reactivated).isEqualTo(guest);
    send(recipient, jsonPost("/api/invitations/preview", Map.of("token", oldToken)), 400);
    var restored =
        send(
            owner,
            jsonPost(expensePath + "/restore", Map.of("expectedVersion", deletedVersion)),
            200);
    assertThat(restored.path("participantUserIds")).isEqualTo(expense.path("participantUserIds"));
    assertThat(send(owner, get(path), 200).path("inactiveGuests").size()).isZero();
    assertThat(send(owner, get(path + "/balances/mine"), 200).get(0).path("netMinor").asLong())
        .isEqualTo(617);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void revocationAndClaimCommitInGroupLockOrder(boolean acceptFirst) throws Exception {
    var owner = account("Owner");
    var recipient = account("Recipient");
    jdbc.update("UPDATE users SET email_verified=true WHERE id=?", recipient.user().id());
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "Revoke race")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    UUID groupId = UUID.fromString(group.path("id").asText());
    var guest = send(owner, jsonPost(path + "/participants", Map.of("displayName", "Guest")), 201);
    var invitation =
        send(
            owner,
            jsonPost(
                path + "/invitations/email",
                Map.of(
                    "email", recipient.user().email(), "participantId", guest.path("id").asText())),
            201);
    String token = mailbox.token(recipient.user().email());
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean();
    var blocker = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            call -> {
              Object result =
                  org.mockito.Mockito.mockingDetails(call.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(call);
              if (first.compareAndSet(false, true)) {
                blocker.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                locked.countDown();
                assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(groupLocks)
        .findByIdForUpdate(groupId);
    var accept =
        jsonPost("/api/invitations/accept", Map.of("token", token, "confirmOwnership", true))
            .header("Authorization", bearer(recipient.accessToken()))
            .header("X-Participant-Version", "1");
    var revoke =
        delete(path + "/invitations/" + invitation.path("id").asText())
            .header("Authorization", bearer(owner.accessToken()))
            .header("X-Participant-Version", "1");
    try (var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var leading =
          requests.submit(() -> mockMvc.perform(acceptFirst ? accept : revoke).andReturn());
      java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> following = null;
      try {
        assertThat(locked.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        following =
            requests.submit(() -> mockMvc.perform(acceptFirst ? revoke : accept).andReturn());
        boolean blocked = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                      Boolean.class,
                      blocker.get()));
          if (blocked) break;
          Thread.yield();
        }
        assertThat(blocked).isTrue();
      } finally {
        release.countDown();
      }
      assertThat(leading.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus())
          .isEqualTo(acceptFirst ? 200 : 204);
      assertThat(following.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus())
          .isEqualTo(acceptFirst ? 409 : 400);
    }
    var finalGroup = send(owner, get(path), 200);
    assertThat(finalGroup.path("members").size()).isEqualTo(acceptFirst ? 2 : 1);
    var statuses = send(owner, get(path + "/invitations"), 200);
    assertThat(statuses.get(0).path("status").asText())
        .isEqualTo(acceptFirst ? "ACCEPTED" : "REVOKED");
    if (acceptFirst)
      assertThat(send(recipient, get(path), 200).path("ownedParticipantIds").toString())
          .contains(guest.path("id").asText());
    else send(recipient, get(path), 403);
  }
}
