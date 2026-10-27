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
      "app.mail.allow-insecure-local=true",
      "app.expenses.version-required-from=2099-01-01T00:00:00Z"
    })
@org.springframework.context.annotation.Import(
    ParticipantInvitationIntegrationTest.MailConfiguration.class)
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class FriendIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired
  ParticipantInvitationIntegrationTest.Mailbox mailbox;

  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

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

  private String group(AuthResponse owner, AuthResponse friend) throws Exception {
    String id =
        send(owner, jsonPost("/api/groups", Map.of("name", "Shared")), 201).path("id").asText();
    send(
        owner,
        jsonPost("/api/groups/" + id + "/members", Map.of("email", friend.user().email())),
        200);
    return id;
  }

  private JsonNode save(AuthResponse owner, String group, UUID participant) throws Exception {
    return send(
        owner,
        jsonPost("/api/friends", Map.of("groupId", group, "participantId", participant)),
        200);
  }

  @Test
  void explicitlySavedFriendPersistsAtZeroAndRepeatedSaveReturnsTheSameBookmark() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
    var saved = save(alice, group, bob.user().id());
    assertThat(saved.path("id").asText()).isEqualTo(bob.user().id().toString());
    assertThat(saved.path("displayName").asText()).isEqualTo("Bob");
    assertThat(saved.path("createdAt").asText()).isNotBlank();
    assertThat(saved.path("sharedGroupCount").asInt()).isEqualTo(1);
    assertThat(saved.path("hasOpenObligations").asBoolean()).isFalse();
    assertThat(saved.path("balances")).hasSize(1);
    assertThat(saved.path("balances").get(0).path("currency").asText()).isEqualTo("USD");
    assertThat(saved.path("balances").get(0).path("netMinor").asLong()).isZero();
    assertThat(saved.has("email")).isFalse();
    assertThat(saved.has("phone")).isFalse();
    assertThat(save(alice, group, bob.user().id())).isEqualTo(saved);
    assertThat(send(alice, get("/api/friends"), 200)).hasSize(1);
  }

  @Test
  void detailBelongsOnlyToTheSavingAccountAndMissingFriendsStayPrivate() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    var saved = save(alice, group, bob.user().id());
    String path = "/api/friends/" + bob.user().id();
    var detail = send(alice, get(path), 200);
    assertThat(detail.path("displayName")).isEqualTo(saved.path("displayName"));
    assertThat(detail.path("sharedGroups")).hasSize(1);
    assertThat(detail.path("sharedGroups").get(0).path("id").asText()).isEqualTo(group);
    assertThat(detail.path("sharedGroups").get(0).path("balances"))
        .isEqualTo(saved.path("balances"));
    assertThat(send(bob, get("/api/friends"), 200)).isEmpty();
    send(bob, get(path), 404);
    send(alice, get("/api/friends/" + UUID.randomUUID()), 404);
  }

  @Test
  void removingABookmarkIsPrivateIdempotentAndAllowsExplicitReaddWithoutChangingTheGroup()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    save(alice, group, bob.user().id());
    var before = send(alice, get("/api/groups/" + group), 200);
    String path = "/api/friends/" + bob.user().id();
    send(bob, delete(path), 204);
    send(alice, get(path), 200);
    send(alice, delete(path), 204);
    send(alice, delete(path), 204);
    send(alice, get(path), 404);
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
    assertThat(send(alice, get("/api/groups/" + group), 200)).isEqualTo(before);
    save(alice, group, bob.user().id());
    assertThat(send(alice, get("/api/friends"), 200)).hasSize(1);
  }

  @Test
  void savingRequiresAnActiveOriginalRegisteredOtherParticipantFromAnAuthorizedSource()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String group = group(alice, bob);
    String foreign = group(outsider, bob);
    var guest =
        send(
            alice,
            jsonPost("/api/groups/" + group + "/participants", Map.of("displayName", "Guest")),
            201);
    for (Object participant :
        java.util.List.of(
            alice.user().id(), outsider.user().id(), guest.path("id").asText(), UUID.randomUUID()))
      send(
          alice,
          jsonPost("/api/friends", Map.of("groupId", group, "participantId", participant)),
          400);
    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", foreign, "participantId", bob.user().id())),
        403);
    send(
        outsider,
        jsonPost(
            "/api/friends", Map.of("groupId", UUID.randomUUID(), "participantId", bob.user().id())),
        403);
    send(alice, delete("/api/groups/" + group + "/members/" + bob.user().id()), 204);
    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", group, "participantId", bob.user().id())),
        400);
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
  }

  private void expense(
      AuthResponse actor, String group, UUID payer, UUID debtor, String currency, long amount)
      throws Exception {
    send(
        actor,
        jsonPost(
            "/api/groups/" + group + "/expenses",
            Map.of(
                "description",
                "Shared cost",
                "currency",
                currency,
                "amountMinor",
                amount,
                "paidByUserId",
                payer,
                "participantUserIds",
                java.util.List.of(debtor))),
        201);
  }

  private void payment(
      AuthResponse actor, String group, UUID payer, UUID payee, String currency, long amount)
      throws Exception {
    send(
        actor,
        jsonPost(
            "/api/groups/" + group + "/payments",
            Map.of(
                "currency",
                currency,
                "amountMinor",
                amount,
                "payerUserId",
                payer,
                "payeeUserId",
                payee)),
        201);
  }

  private long amount(JsonNode rows, String currency) {
    for (var row : rows)
      if (row.path("currency").asText().equals(currency)) return row.path("netMinor").asLong();
    throw new AssertionError("Missing currency " + currency + " in " + rows);
  }

  @Test
  void oppositeGroupsRemainOpenAtZeroOverallAndSettlementRetainsAllLedgerCurrenciesAndTheBookmark()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String first = group(alice, bob);
    String second = group(alice, bob);
    expense(alice, first, alice.user().id(), bob.user().id(), "USD", 100);
    expense(alice, second, bob.user().id(), alice.user().id(), "USD", 100);
    expense(alice, first, alice.user().id(), bob.user().id(), "KWD", 1234);
    expense(alice, first, bob.user().id(), alice.user().id(), "JPY", 500);
    var saved = save(alice, first, bob.user().id());
    assertThat(saved.path("sharedGroupCount").asInt()).isEqualTo(2);
    assertThat(amount(saved.path("balances"), "USD")).isZero();
    assertThat(amount(saved.path("balances"), "KWD")).isEqualTo(1234);
    assertThat(amount(saved.path("balances"), "JPY")).isEqualTo(-500);
    assertThat(saved.path("hasOpenObligations").asBoolean()).isTrue();
    var detail = send(alice, get("/api/friends/" + bob.user().id()), 200);
    for (var scope : detail.path("sharedGroups")) {
      String id = scope.path("id").asText();
      var mine = send(alice, get("/api/groups/" + id + "/balances/mine"), 200);
      assertThat(amount(scope.path("balances"), "USD")).isEqualTo(amount(mine, "USD"));
    }
    payment(alice, first, bob.user().id(), alice.user().id(), "USD", 100);
    payment(alice, second, alice.user().id(), bob.user().id(), "USD", 100);
    payment(alice, first, bob.user().id(), alice.user().id(), "KWD", 1234);
    payment(alice, first, alice.user().id(), bob.user().id(), "JPY", 500);
    var settled = send(alice, get("/api/friends"), 200).get(0);
    assertThat(settled.path("createdAt")).isEqualTo(saved.path("createdAt"));
    assertThat(settled.path("hasOpenObligations").asBoolean()).isFalse();
    assertThat(settled.path("balances")).hasSize(3);
    for (var row : settled.path("balances")) assertThat(row.path("netMinor").asLong()).isZero();
  }

  @Test
  void friendDetailTracksSelectedObligationModeWithoutRewritingRecordedExpenses() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String group = group(alice, bob);
    String path = "/api/groups/" + group;
    send(alice, jsonPost(path + "/members", Map.of("email", carol.user().email())), 200);
    expense(alice, group, alice.user().id(), bob.user().id(), "USD", 100);
    expense(alice, group, bob.user().id(), carol.user().id(), "USD", 100);
    expense(alice, group, carol.user().id(), alice.user().id(), "USD", 100);
    var history = send(alice, get(path + "/expenses"), 200);
    var saved = save(alice, group, bob.user().id());
    assertThat(amount(saved.path("balances"), "USD")).isZero();
    assertThat(saved.path("hasOpenObligations").asBoolean()).isFalse();
    send(
        alice,
        patch(path)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json(Map.of("simplifyDebts", false))),
        200);
    var direct = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(direct.path("hasOpenObligations").asBoolean()).isTrue();
    assertThat(direct.path("sharedGroups").get(0).path("simplifyDebts").asBoolean()).isFalse();
    assertThat(amount(direct.path("balances"), "USD")).isEqualTo(100);
    var mine = send(alice, get(path + "/balances/mine"), 200);
    for (var row : mine)
      if (row.path("user").path("id").asText().equals(bob.user().id().toString()))
        assertThat(amount(direct.path("balances"), "USD")).isEqualTo(row.path("netMinor").asLong());
    send(
        alice,
        patch(path)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(json(Map.of("simplifyDebts", true))),
        200);
    assertThat(send(alice, get("/api/friends"), 200).get(0).path("hasOpenObligations").asBoolean())
        .isFalse();
    assertThat(send(alice, get(path + "/expenses"), 200)).isEqualTo(history);
  }

  @Test
  void accessLossAndGroupDeletionLeaveTheSavedLabelWithoutExposingFormerGroupHistory()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String retained = group(bob, alice);
    String deleted = group(alice, bob);
    expense(alice, retained, alice.user().id(), bob.user().id(), "EUR", 750);
    payment(alice, retained, bob.user().id(), alice.user().id(), "EUR", 750);
    var history = send(bob, get("/api/groups/" + retained + "/expenses"), 200);
    var saved = save(alice, retained, bob.user().id());
    send(
        bob,
        patch("/api/me")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(
                json(Map.of("displayName", "New private profile", "phone", "private-contact"))),
        200);
    assertThat(save(alice, deleted, bob.user().id())).isEqualTo(saved);
    send(bob, delete("/api/groups/" + retained + "/members/" + alice.user().id()), 204);
    var remaining = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(remaining.path("sharedGroups")).hasSize(1);
    assertThat(remaining.path("sharedGroups").get(0).path("id").asText()).isEqualTo(deleted);
    send(alice, get("/api/groups/" + retained + "/expenses"), 403);
    send(alice, delete("/api/groups/" + deleted), 204);
    var noData = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(noData.path("displayName").asText()).isEqualTo("Bob");
    assertThat(noData.path("createdAt")).isEqualTo(saved.path("createdAt"));
    assertThat(noData.path("sharedGroupCount").asInt()).isZero();
    assertThat(noData.path("balances")).isEmpty();
    assertThat(noData.path("sharedGroups")).isEmpty();
    assertThat(noData.toString())
        .doesNotContain("private-contact", bob.user().email(), "New private profile");
    assertThat(send(alice, get("/api/friends"), 200)).hasSize(1);
    assertThat(send(bob, get("/api/groups/" + retained + "/expenses"), 200)).isEqualTo(history);
  }

  @Test
  void capabilitiesRejectUnsupportedResponsesBeforeSavingAndOwnerPrivacyPrecedesTheUpgradePrompt()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    var command = Map.of("groupId", group, "participantId", bob.user().id());
    mockMvc.perform(get("/api/friends")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/friends").header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPost("/api/friends", command).header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
    expense(alice, group, alice.user().id(), bob.user().id(), "KWD", 1234);
    mockMvc
        .perform(
            jsonPost("/api/friends", command)
                .header("Authorization", bearer(alice.accessToken()))
                .header("X-Participant-Version", "1"))
        .andExpect(status().isConflict());
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
    save(alice, group, bob.user().id());
    String path = "/api/friends/" + bob.user().id();
    mockMvc
        .perform(get(path).header("Authorization", bearer(bob.accessToken())))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get(path).header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            get(path)
                .header("Authorization", bearer(alice.accessToken()))
                .header("X-Participant-Version", "1"))
        .andExpect(status().isConflict());
    mockMvc
        .perform(delete(path).header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isConflict());
    assertThat(send(alice, get(path), 200).path("id").asText())
        .isEqualTo(bob.user().id().toString());
  }

  @Test
  void unsafeAccessibleTotalsFailAsUnavailableBeforeAnyBookmarkIsWritten() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String first = group(alice, bob);
    String second = group(alice, bob);
    expense(alice, first, alice.user().id(), bob.user().id(), "USD", 100);
    expense(alice, second, alice.user().id(), bob.user().id(), "USD", 100);
    save(alice, first, bob.user().id());
    // Imported legacy fixture: individually exact group totals whose combined scope is unsafe.
    for (String group : java.util.List.of(first, second)) {
      jdbc.update(
          "UPDATE expenses SET amount_cents=5000000000000000 WHERE group_id=?",
          UUID.fromString(group));
      jdbc.update(
          "UPDATE expense_participants SET share_cents=5000000000000000 WHERE expense_id IN (SELECT id FROM expenses WHERE group_id=?)",
          UUID.fromString(group));
    }
    var unavailable = send(alice, get("/api/friends"), 409);
    assertThat(unavailable.path("message").asText()).contains("exact integer");
    send(alice, get("/api/friends/" + bob.user().id()), 409);
    send(alice, delete("/api/friends/" + bob.user().id()), 204);
    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", first, "participantId", bob.user().id())),
        409);
    send(alice, delete("/api/groups/" + first), 204);
    send(alice, delete("/api/groups/" + second), 204);
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
  }

  @Test
  void verifiedClaimedAliasesSaveOneCanonicalAccountAndNeverRewriteOriginalFinancialFacts()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    String path = "/api/groups/" + group;
    var guest =
        send(alice, jsonPost(path + "/participants", Map.of("displayName", "Original guest")), 201);
    UUID alias = UUID.fromString(guest.path("id").asText());
    var expense =
        send(
            alice,
            jsonPost(
                path + "/expenses",
                Map.of(
                    "description",
                    "Before claim",
                    "currency",
                    "KWD",
                    "amountMinor",
                    1234,
                    "paidByUserId",
                    alice.user().id(),
                    "participantUserIds",
                    java.util.List.of(alias))),
            201);
    String expensePath = path + "/expenses/" + expense.path("id").asText();
    var history = send(alice, get(expensePath + "/history"), 200);
    send(
        alice,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", bob.user().email(), "participantId", alias)),
        201);
    String invitation = mailbox.token(bob.user().email());
    send(bob, jsonPost("/api/me/email-verification-requests", Map.of()), 202);
    send(
        bob,
        jsonPost(
            "/api/auth/email-verifications", Map.of("token", mailbox.token(bob.user().email()))),
        204);
    send(
        bob,
        jsonPost("/api/invitations/accept", Map.of("token", invitation, "confirmOwnership", true)),
        200);
    var saved = save(alice, group, alias);
    assertThat(saved.path("id").asText()).isEqualTo(bob.user().id().toString());
    assertThat(saved.path("displayName").asText()).isEqualTo("Bob");
    assertThat(amount(saved.path("balances"), "KWD")).isEqualTo(1234);
    assertThat(save(alice, group, bob.user().id())).isEqualTo(saved);
    assertThat(send(alice, get("/api/friends"), 200)).hasSize(1);
    send(bob, jsonPost("/api/friends", Map.of("groupId", group, "participantId", alias)), 400);
    var retained = send(alice, get(expensePath), 200);
    assertThat(retained.path("participantUserIds")).isEqualTo(expense.path("participantUserIds"));
    assertThat(retained.path("amountMinor")).isEqualTo(expense.path("amountMinor"));
    assertThat(retained.path("version")).isEqualTo(expense.path("version"));
    assertThat(send(alice, get(expensePath + "/history"), 200)).isEqualTo(history);
  }

  @Test
  void aSaveWaitingBehindSourceMembershipRemovalReauthorizesBeforeCreatingTheBookmark()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    // Only coordinate lock order here; mutations and observations stay at authenticated HTTP.
    try (var coordination = dataSource.getConnection();
        var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      coordination.setAutoCommit(false);
      try (var statement =
          coordination.prepareStatement("SELECT id FROM groups WHERE id=? FOR UPDATE")) {
        statement.setObject(1, UUID.fromString(group));
        statement.executeQuery().close();
      }
      try {
        var removal =
            requests.submit(
                () ->
                    send(
                        alice,
                        delete("/api/groups/" + group + "/members/" + bob.user().id()),
                        204));
        awaitWaitingSourceWriters(1);
        var save =
            requests.submit(
                () ->
                    send(
                        bob,
                        jsonPost(
                            "/api/friends",
                            Map.of("groupId", group, "participantId", alice.user().id())),
                        403));
        awaitWaitingSourceWriters(2);
        coordination.commit();
        removal.get(20, java.util.concurrent.TimeUnit.SECONDS);
        save.get(20, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        coordination.rollback();
      }
    }
    assertThat(send(bob, get("/api/friends"), 200)).isEmpty();
    send(bob, get("/api/groups/" + group), 403);
    assertThat(send(alice, get("/api/groups/" + group), 200).path("members")).hasSize(1);
  }

  private void awaitWaitingSourceWriters(int count) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      int waiting =
          jdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%groups%'",
              Integer.class);
      if (waiting >= count) return;
      Thread.sleep(20);
    }
    throw new AssertionError("Expected competing HTTP source writers to wait on the group row");
  }

  @Test
  void concurrentRemovalAfterAnIdempotentSaveInsertReturnsReloadGuidanceInsteadOfAServerError()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = group(alice, bob);
    save(alice, group, bob.user().id());
    long key = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    String function = "friend_save_race_" + UUID.randomUUID().toString().replace("-", "");
    // A statement trigger pauses only the SQL boundary; both directory mutations use HTTP.
    jdbc.execute(
        "CREATE FUNCTION "
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock("
            + key
            + "); RETURN NULL; END $$");
    jdbc.execute(
        "CREATE TRIGGER "
            + function
            + " AFTER INSERT ON friends FOR EACH STATEMENT EXECUTE FUNCTION "
            + function
            + "()");
    try (var coordination = dataSource.getConnection();
        var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      coordination.setAutoCommit(false);
      int blocker;
      try (var statement = coordination.createStatement();
          var result =
              statement.executeQuery(
                  "SELECT pg_backend_pid(),pg_advisory_xact_lock(" + key + ")")) {
        result.next();
        blocker = result.getInt(1);
      }
      try {
        var save =
            requests.submit(
                () ->
                    send(
                        alice,
                        jsonPost(
                            "/api/friends",
                            Map.of("groupId", group, "participantId", bob.user().id())),
                        409));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
          blocked =
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                  Boolean.class,
                  blocker);
          if (blocked) break;
          Thread.sleep(20);
        }
        assertThat(blocked).as("save waits after its idempotent INSERT").isTrue();
        send(alice, delete("/api/friends/" + bob.user().id()), 204);
        coordination.commit();
        assertThat(save.get(20, java.util.concurrent.TimeUnit.SECONDS).path("message").asText())
            .contains("Reload", "review");
      } finally {
        coordination.rollback();
      }
    } finally {
      jdbc.execute("DROP TRIGGER " + function + " ON friends");
      jdbc.execute("DROP FUNCTION " + function + "()");
    }
    assertThat(send(alice, get("/api/friends"), 200)).isEmpty();
  }
}
