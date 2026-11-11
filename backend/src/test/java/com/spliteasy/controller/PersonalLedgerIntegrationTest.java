package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PersonalLedgerIntegrationTest extends AbstractIntegrationTest {

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

  /** Two accounts sharing one real group, each saving the other as a friend. */
  private String friends(AuthResponse a, AuthResponse b) throws Exception {
    String group =
        send(a, jsonPost("/api/groups", Map.of("name", "Shared")), 201).path("id").asText();
    send(a, jsonPost("/api/groups/" + group + "/members", Map.of("email", b.user().email())), 200);
    save(a, group, b.user().id());
    save(b, group, a.user().id());
    return group;
  }

  private void save(AuthResponse owner, String group, UUID participant) throws Exception {
    send(
        owner,
        jsonPost("/api/friends", Map.of("groupId", group, "participantId", participant)),
        200);
  }

  private Map<String, Object> expense(UUID payer, String currency, long amount, UUID... split) {
    var body = new HashMap<String, Object>();
    body.put("description", "Dinner");
    body.put("currency", currency);
    body.put("amountMinor", amount);
    body.put("paidByUserId", payer);
    body.put("participantUserIds", List.of(split));
    return body;
  }

  private String expenses(AuthResponse friend) {
    return "/api/friends/" + friend.user().id() + "/expenses";
  }

  @Test
  void personalExpenseReachesBothFriendsDetailAsASeparatePersonalScope() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group = friends(alice, bob);

    var created =
        send(
            alice,
            jsonPost(
                expenses(bob),
                expense(alice.user().id(), "USD", 1000, alice.user().id(), bob.user().id())),
            201);

    var aliceView = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(aliceView.path("personalBalances").get(0).path("currency").asText())
        .isEqualTo("USD");
    assertThat(aliceView.path("personalBalances").get(0).path("netMinor").asLong()).isEqualTo(500);
    assertThat(aliceView.path("balances").get(0).path("netMinor").asLong()).isEqualTo(500);
    assertThat(aliceView.path("sharedGroupCount").asInt()).isEqualTo(1);
    assertThat(aliceView.path("sharedGroups")).hasSize(1);
    assertThat(aliceView.path("sharedGroups").get(0).path("id").asText()).isEqualTo(group);
    assertThat(aliceView.path("hasOpenObligations").asBoolean()).isTrue();

    var bobView = send(bob, get("/api/friends/" + alice.user().id()), 200);
    assertThat(bobView.path("personalBalances").get(0).path("netMinor").asLong()).isEqualTo(-500);

    assertThat(send(alice, get(expenses(bob)), 200)).hasSize(1);
    var bobList = send(bob, get(expenses(alice)), 200);
    assertThat(bobList).hasSize(1);
    assertThat(bobList.get(0).path("id")).isEqualTo(created.path("id"));
  }

  @Test
  void personalLedgerIsNeverAGroupButItsMoneyStillReachesDashboardTotals() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String group = friends(alice, bob);
    String ledger =
        send(
                alice,
                jsonPost(
                    expenses(bob),
                    expense(alice.user().id(), "USD", 1000, alice.user().id(), bob.user().id())),
                201)
            .path("groupId")
            .asText();
    String base = "/api/groups/" + ledger;

    for (var member : List.of(alice, bob)) {
      for (var request :
          List.of(
              get(base),
              patch(base).contentType("application/json").content("{\"name\":\"x\"}"),
              delete(base),
              jsonPost(base + "/members", Map.of("email", outsider.user().email())),
              delete(base + "/members/" + bob.user().id()),
              jsonPost(base + "/archive", Map.of()),
              jsonPost(base + "/unarchive", Map.of()),
              jsonPost(base + "/restore", Map.of("expectedVersion", 0)),
              get(base + "/invitations"),
              jsonPost(base + "/invitations/links", Map.of()),
              jsonPost(base + "/participants", Map.of("displayName", "Guest")),
              get(base + "/expenses"),
              get(base + "/balances"),
              get(base + "/payments"),
              get(base + "/debt-simplification"))) send(member, request, 404);
      var groups = send(member, get("/api/groups"), 200);
      assertThat(groups).hasSize(1);
      assertThat(groups.get(0).path("id").asText()).isEqualTo(group);
      var dashboard = send(member, get("/api/dashboard"), 200);
      assertThat(dashboard.path("groupCount").asInt()).isEqualTo(1);
      assertThat(dashboard.path("groups")).hasSize(1);
      assertThat(dashboard.path("settlements")).isEmpty();
      assertThat(dashboard.path("activity").findValuesAsString("groupId")).doesNotContain(ledger);
      long net = member == alice ? 500 : -500;
      assertThat(dashboard.path("totals").get(0).path("totalNetMinor").asLong()).isEqualTo(net);
      assertThat(dashboard.path("people").get(0).path("netMinor").asLong()).isEqualTo(net);
    }
    // An outsider learns nothing: the same 403 as for any group they are not in.
    send(outsider, get(base), 403);
    send(outsider, get(base + "/expenses"), 403);
    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", ledger, "participantId", bob.user().id())),
        404);
  }

  @Test
  void onlyTheTwoAccountsReachAPairLedgerAndCreationNeedsASavedRegisteredOtherFriend()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String group = friends(alice, bob);
    send(
        alice,
        jsonPost("/api/groups/" + group + "/members", Map.of("email", carol.user().email())),
        200);
    UUID a = alice.user().id();
    UUID b = bob.user().id();
    // A participant outside the pair is rejected before any write, leaving no ledger behind.
    send(alice, jsonPost(expenses(bob), expense(a, "USD", 900, a, carol.user().id())), 400);
    send(alice, jsonPost(expenses(bob), expense(carol.user().id(), "USD", 900, a, b)), 400);
    assertThat(send(alice, get(expenses(bob)), 200)).isEmpty();
    var created = send(alice, jsonPost(expenses(bob), expense(a, "USD", 900, a, b)), 201);
    String item = expenses(bob) + "/" + created.path("id").asText();

    // Carol shares a group with both but saved neither: every personal route is 403 for her.
    for (var friend : List.of(alice, bob)) {
      String path = expenses(friend);
      String id = path + "/" + created.path("id").asText();
      for (var request :
          List.of(
              get(path),
              get(path + "/deleted"),
              get(id),
              put(id).contentType("application/json").content(json(expense(a, "USD", 1, a, b))),
              delete(id),
              jsonPost(id + "/restore", Map.of("expectedVersion", 0)),
              jsonPost(path, expense(carol.user().id(), "USD", 100, carol.user().id(), b))))
        send(carol, request, 403);
    }
    // Self, including an owned participant id, and an unclaimed guest are refused with guidance.
    send(alice, jsonPost("/api/friends/" + a + "/expenses", expense(a, "USD", 100, a)), 400);
    String guest =
        send(
                alice,
                jsonPost("/api/groups/" + group + "/participants", Map.of("displayName", "Guest")),
                201)
            .path("id")
            .asText();
    var refused =
        send(
            alice, jsonPost("/api/friends/" + guest + "/expenses", expense(a, "USD", 100, a)), 400);
    assertThat(refused.path("message").asText()).contains("claim");
    send(
        alice,
        jsonPost("/api/friends/" + UUID.randomUUID() + "/expenses", expense(a, "USD", 1, a)),
        403);

    // Removing either bookmark never removes the jointly owned ledger or its money.
    send(alice, delete("/api/friends/" + b), 204);
    send(bob, delete("/api/friends/" + a), 204);
    assertThat(send(alice, get(expenses(bob)), 200)).hasSize(1);
    assertThat(send(bob, get(expenses(alice)), 200)).hasSize(1);
    send(bob, get(item.replace(b.toString(), a.toString())), 200);
    var dashboard = send(bob, get("/api/dashboard"), 200);
    assertThat(dashboard.path("totals").get(0).path("totalNetMinor").asLong()).isEqualTo(-450);
  }

  private long personalNet(AuthResponse viewer, AuthResponse friend, String currency)
      throws Exception {
    for (var row :
        send(viewer, get("/api/friends/" + friend.user().id()), 200).path("personalBalances"))
      if (row.path("currency").asText().equals(currency)) return row.path("netMinor").asLong();
    return 0;
  }

  @Test
  void personalExpensesKeepTheLedgerContractForCurrencyIntentRetriesVersionsAndRecovery()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    friends(alice, bob);
    UUID a = alice.user().id();
    UUID b = bob.user().id();
    UUID operation = UUID.randomUUID();
    var body = expense(a, "USD", 1000, a, b);
    body.put("operationId", operation);
    var created = send(alice, jsonPost(expenses(bob), body), 201);
    // Keyed replay returns the original receipt; a changed intent under the same key is 409.
    assertThat(send(alice, jsonPost(expenses(bob), body), 201)).isEqualTo(created);
    var changed = new HashMap<>(body);
    changed.put("amountMinor", 2000);
    send(alice, jsonPost(expenses(bob), changed), 409);
    send(bob, jsonPost(expenses(alice), expense(b, "JPY", 300, a, b)), 201);
    // USD and JPY stay separate rows, never combined.
    assertThat(personalNet(alice, bob, "USD")).isEqualTo(500);
    assertThat(personalNet(alice, bob, "JPY")).isEqualTo(-150);

    // Percentage intent round-trips through a full-replace edit.
    String item = expenses(bob) + "/" + created.path("id").asText();
    var edit = expense(a, "USD", 1000);
    edit.put("participantUserIds", null);
    edit.put("splitType", "PERCENTAGE");
    edit.put(
        "splits", List.of(Map.of("userId", a, "value", 2500), Map.of("userId", b, "value", 7500)));
    edit.put("expectedVersion", created.path("version").asLong());
    var edited = send(alice, put(item).contentType("application/json").content(json(edit)), 200);
    assertThat(edited.path("splitType").asText()).isEqualTo("PERCENTAGE");
    assertThat(
            send(bob, get(expenses(alice) + "/" + created.path("id").asText()), 200).path("splits"))
        .isEqualTo(edited.path("splits"));
    assertThat(personalNet(alice, bob, "USD")).isEqualTo(750);
    // The edit advanced the version, so the original one is now stale.
    send(alice, put(item).contentType("application/json").content(json(edit)), 409);

    long version = edited.path("version").asLong();
    send(
        bob,
        delete(expenses(alice) + "/" + created.path("id").asText() + "?expectedVersion=" + version),
        204);
    assertThat(personalNet(alice, bob, "USD")).isZero();
    var deleted = send(alice, get(expenses(bob) + "/deleted"), 200);
    assertThat(deleted).hasSize(1);
    long deletedVersion = deleted.get(0).path("expense").path("version").asLong();
    send(alice, jsonPost(item + "/restore", Map.of("expectedVersion", deletedVersion)), 200);
    send(alice, jsonPost(item + "/restore", Map.of("expectedVersion", deletedVersion)), 409);
    assertThat(personalNet(alice, bob, "USD")).isEqualTo(750);
  }

  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  /**
   * A concurrent first writer holds the pair's uncommitted claim. The request's own claim must wait
   * on PostgreSQL, lose without an aborted transaction, and land in the winner's ledger.
   */
  @Test
  void aFirstWriteThatLosesTheConcurrentPairClaimReusesTheWinningLedger() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    friends(alice, bob);
    UUID a = alice.user().id();
    UUID b = bob.user().id();
    UUID winner = UUID.randomUUID();
    boolean aFirst = a.toString().compareTo(b.toString()) < 0;
    var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute(
            "INSERT INTO groups(id,name,created_by,created_at,kind) VALUES('%s','Personal ledger','%s',now(),'PERSONAL')"
                .formatted(winner, b));
        for (UUID member : List.of(a, b))
          statement.execute(
              ("INSERT INTO group_memberships(id,group_id,user_id,joined_at) VALUES(gen_random_uuid(),'%s','%s',now());"
                      + "INSERT INTO group_participants(group_id,participant_id,active,added_at) VALUES('%s','%s',true,now())")
                  .formatted(winner, member, winner, member));
        statement.execute(
            "INSERT INTO personal_ledgers(account_low,account_high,group_id) VALUES('%s','%s','%s')"
                .formatted(aFirst ? a : b, aFirst ? b : a, winner));
      }
      int blocker = jdbcPid(connection);
      var request =
          pool.submit(
              () -> send(alice, jsonPost(expenses(bob), expense(a, "USD", 100, a, b)), 201));
      awaitBlockedBy(blocker);
      connection.commit();
      assertThat(request.get().path("groupId").asText()).isEqualTo(winner.toString());
    } finally {
      pool.shutdown();
    }
    assertThat(send(bob, get(expenses(alice)), 200)).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM groups g JOIN group_memberships m ON m.group_id=g.id WHERE g.kind='PERSONAL' AND m.user_id=?",
                Long.class,
                a))
        .isEqualTo(1);
  }

  private int jdbcPid(java.sql.Connection connection) throws Exception {
    try (var rs = connection.createStatement().executeQuery("select pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private void awaitBlockedBy(int blockerPid) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    boolean blocked;
    do {
      blocked =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                  Boolean.class,
                  blockerPid));
      if (blocked) break;
      Thread.yield();
    } while (System.nanoTime() < deadline);
    assertThat(blocked).as("the request's claim waits for the concurrent pair claim").isTrue();
  }
}
