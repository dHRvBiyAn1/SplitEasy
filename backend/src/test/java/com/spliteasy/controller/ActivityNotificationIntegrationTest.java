package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** H03: durable activity events and in-app notifications written with the financial revision. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ActivityNotificationIntegrationTest extends AbstractIntegrationTest {
  @Autowired JdbcTemplate jdbc;

  AuthResponse account(String name) throws Exception {
    return register(UUID.randomUUID() + "@example.test", "password123", name);
  }

  JsonNode send(AuthResponse who, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(who.accessToken()))
                    .header("X-Ledger-Version", "2")
                    .header("X-Participant-Version", "1"))
            .andExpect(status().is(expected))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  String group(AuthResponse owner, AuthResponse... members) throws Exception {
    String group =
        send(owner, jsonPost("/api/groups", Map.of("name", "Trip")), 201).path("id").asText();
    for (var m : members)
      send(
          owner,
          jsonPost("/api/groups/" + group + "/members", Map.of("email", m.user().email())),
          200);
    return group;
  }

  Map<String, Object> expenseBody(AuthResponse payer, long amount) {
    var body = new HashMap<String, Object>();
    body.put("description", "Dinner");
    body.put("currency", "USD");
    body.put("amountMinor", amount);
    body.put("paidByUserId", payer.user().id());
    body.put("splitType", "EQUAL");
    return body;
  }

  List<String> kinds(AuthResponse who, String query) throws Exception {
    var items = send(who, get("/api/activity" + query), 200).path("items");
    var kinds = new ArrayList<String>();
    items.forEach(i -> kinds.add(i.path("kind").asText()));
    return kinds;
  }

  long unread(AuthResponse who) throws Exception {
    return send(who, get("/api/notifications/unread-count"), 200).path("count").asLong();
  }

  long events() {
    return jdbc.queryForObject("SELECT count(*) FROM activity_events", Long.class);
  }

  @Test
  void everyFinancialAndLifecycleWriteProducesExactlyOneEventAndNotifiesOthersOnly()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String g = group(alice, bob, carol);
    String base = "/api/groups/" + g;

    var expense = send(alice, jsonPost(base + "/expenses", expenseBody(alice, 3000)), 201);
    String id = expense.path("id").asText();
    var edit = expenseBody(alice, 4500);
    edit.put("expectedVersion", expense.path("version").asLong());
    var edited =
        send(
            alice,
            put(base + "/expenses/" + id).contentType("application/json").content(json(edit)),
            200);
    send(
        alice,
        delete(base + "/expenses/" + id).param("expectedVersion", edited.path("version").asText()),
        204);
    send(
        alice,
        jsonPost(
            base + "/expenses/" + id + "/restore",
            Map.of("expectedVersion", edited.path("version").asLong() + 1)),
        200);
    var payment = new HashMap<String, Object>();
    payment.put("payerUserId", bob.user().id());
    payment.put("payeeUserId", alice.user().id());
    payment.put("amountMinor", 100);
    payment.put("currency", "USD");
    payment.put("paidOn", "2026-03-02");
    send(bob, jsonPost(base + "/payments", payment), 201);
    send(
        alice,
        jsonPost(
            base + "/expenses/" + id + "/refunds",
            Map.of("amountMinor", 50, "currency", "USD", "refundedOn", "2026-03-02")),
        201);
    send(alice, jsonPost(base + "/archive", Map.of()), 200);
    send(alice, jsonPost(base + "/unarchive", Map.of()), 200);

    assertThat(kinds(alice, "?groupId=" + g))
        .containsExactly(
            "GROUP_UNARCHIVED",
            "GROUP_ARCHIVED",
            "REFUND_CREATED",
            "PAYMENT_CREATED",
            "EXPENSE_RESTORED",
            "EXPENSE_DELETED",
            "EXPENSE_REPLACED",
            "EXPENSE_CREATED",
            "MEMBER_ADDED",
            "MEMBER_ADDED");
    // Alice recorded all but Bob's payment; Bob (own payment) and Carol saw the rest.
    assertThat(unread(alice)).isEqualTo(1);
    assertThat(unread(bob)).isEqualTo(9);
    assertThat(unread(carol)).isEqualTo(9);
    var first = send(alice, get("/api/activity?groupId=" + g + "&limit=1"), 200);
    assertThat(first.path("items").get(0).path("actor").path("displayName").asText())
        .isEqualTo("Alice");
    assertThat(first.path("nextCursor").asText()).isNotBlank();
  }

  @Test
  void rejectedWriteLeavesNoEvent() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob);
    long before = events();
    send(alice, jsonPost("/api/groups/" + g + "/expenses", expenseBody(alice, -5)), 400);
    assertThat(events()).isEqualTo(before);
  }

  @Test
  void keyedReplayWritesNothing() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob);
    var body = expenseBody(alice, 1000);
    body.put("operationId", UUID.randomUUID());
    send(alice, jsonPost("/api/groups/" + g + "/expenses", body), 201);
    long after = events();
    send(alice, jsonPost("/api/groups/" + g + "/expenses", body), 201);
    assertThat(events()).isEqualTo(after);
  }

  @Test
  void preferencesFilterRecipientsAndReadStateIsPrivate() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String g = group(alice, bob, carol);
    assertThat(send(bob, get("/api/me/notification-preferences"), 200).path("expenses").asBoolean())
        .isTrue();
    send(
        bob,
        put("/api/me/notification-preferences")
            .contentType("application/json")
            .content("{\"expenses\":false,\"payments\":true,\"groupChanges\":true}"),
        200);
    long bobBefore = unread(bob);
    long carolBefore = unread(carol);
    send(alice, jsonPost("/api/groups/" + g + "/expenses", expenseBody(alice, 900)), 201);
    assertThat(unread(bob)).isEqualTo(bobBefore);
    assertThat(unread(carol)).isEqualTo(carolBefore + 1);

    var list = send(carol, get("/api/notifications?unreadOnly=true"), 200);
    String nid = list.path("items").get(0).path("id").asText();
    assertThat(list.path("items").get(0).path("event").path("kind").asText())
        .isEqualTo("EXPENSE_CREATED");
    send(bob, jsonPost("/api/notifications/" + nid + "/read", Map.of()), 404);
    send(carol, jsonPost("/api/notifications/" + nid + "/read", Map.of()), 204);
    assertThat(unread(carol)).isEqualTo(carolBefore);
    send(carol, jsonPost("/api/notifications/read-all", Map.of()), 204);
    assertThat(unread(carol)).isZero();
  }

  @Test
  void activityIsMemberOnlyAndHidesPersonalLedgerGroupIds() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String g = group(alice, bob);
    send(outsider, get("/api/activity?groupId=" + g), 403);
    send(outsider, get("/api/activity?groupId=" + UUID.randomUUID()), 403);

    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", g, "participantId", bob.user().id())),
        200);
    send(
        bob,
        jsonPost("/api/friends", Map.of("groupId", g, "participantId", alice.user().id())),
        200);
    var personal =
        send(
            alice,
            jsonPost("/api/friends/" + bob.user().id() + "/expenses", expenseBody(alice, 800)),
            201);
    String ledger = personal.path("groupId").asText();
    send(alice, get("/api/activity?groupId=" + ledger), 404);
    // The personal entry reaches the account feed with no group name, and notifies the friend.
    var feed = send(bob, get("/api/activity"), 200).path("items").get(0);
    assertThat(feed.path("kind").asText()).isEqualTo("EXPENSE_CREATED");
    assertThat(feed.path("groupName").isNull()).isTrue();
    assertThat(unread(bob)).isGreaterThanOrEqualTo(1);
  }

  @Test
  void deletedGroupLeavesTheFeedButKeepsItsNotificationAndRestoreReturns() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob);
    send(alice, delete("/api/groups/" + g), 204);
    assertThat(kinds(bob, "")).doesNotContain("GROUP_DELETED");
    var notes = send(bob, get("/api/notifications"), 200).path("items");
    assertThat(notes.get(0).path("event").path("kind").asText()).isEqualTo("GROUP_DELETED");
    send(alice, jsonPost("/api/groups/" + g + "/restore", Map.of("expectedVersion", 1)), 200);
    assertThat(kinds(bob, "?groupId=" + g)).contains("GROUP_RESTORED");
  }
}
