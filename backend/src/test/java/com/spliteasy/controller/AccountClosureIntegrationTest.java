package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.LoginRequest;
import java.util.ArrayList;
import java.util.List;
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
class AccountClosureIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired
  ParticipantInvitationIntegrationTest.Mailbox mailbox;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.spliteasy.service.MembershipGuard membershipGuard;

  private final List<String> bodies = new ArrayList<>();

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
    bodies.add(body);
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  private String group(AuthResponse owner, String name, AuthResponse... members) throws Exception {
    String id = send(owner, jsonPost("/api/groups", Map.of("name", name)), 201).path("id").asText();
    for (var m : members)
      send(
          owner,
          jsonPost("/api/groups/" + id + "/members", Map.of("email", m.user().email())),
          200);
    return id;
  }

  private JsonNode expense(
      AuthResponse actor, String group, UUID payer, UUID debtor, UUID operation) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("description", "Dinner");
    body.put("currency", "USD");
    body.put("amountMinor", 1000);
    body.put("paidByUserId", payer);
    body.put("participantUserIds", List.of(debtor));
    if (operation != null) body.put("operationId", operation);
    return send(actor, jsonPost("/api/groups/" + group + "/expenses", body), 201);
  }

  private void payment(AuthResponse actor, String group, UUID payer, UUID payee) throws Exception {
    send(
        actor,
        jsonPost(
            "/api/groups/" + group + "/payments",
            Map.of(
                "currency",
                "USD",
                "amountMinor",
                1000,
                "payerUserId",
                payer,
                "payeeUserId",
                payee)),
        201);
  }

  private void close(AuthResponse actor, String password, int expected) throws Exception {
    send(actor, jsonPost("/api/me/closure", Map.of("currentPassword", password)), expected);
  }

  @Test
  void unsettledClosureIsRefusedNamingTheGroupAndChangesNothing() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var alice = register(email, "password123", "Alice");
    var bob = register(UUID.randomUUID() + "@example.test", "password123", "Bob");
    String g = group(alice, "Trip to Rome", bob);
    expense(alice, g, alice.user().id(), bob.user().id(), null);

    var refused =
        send(alice, jsonPost("/api/me/closure", Map.of("currentPassword", "password123")), 409);
    assertThat(refused.path("message").asText()).contains("Trip to Rome");
    send(alice, get("/api/me"), 200);
    login(email, "password123");
    send(bob, get("/api/groups/" + g), 200);
    assertThat(
            jdbc.queryForObject("select closed_at from users where email=?", Object.class, email))
        .isNull();
  }

  @Test
  void wrongPasswordIsRejectedAndNothingChanges() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var alice = register(email, "password123", "Alice");
    close(alice, "not-the-password", 400);
    send(alice, get("/api/me"), 200);
    assertThat(send(alice, get("/api/me"), 200).path("displayName").asText()).isEqualTo("Alice");
  }

  @Test
  void settledClosurePreservesHistoryHidesIdentityAndFreesTheEmail() throws Exception {
    String email = UUID.randomUUID() + "@example.test";
    var alice = register(email, "password123", "Alice");
    var bob = register(UUID.randomUUID() + "@example.test", "password123", "Bob");
    var carol = register(UUID.randomUUID() + "@example.test", "password123", "Carol");
    String shared = group(alice, "Shared", bob, carol);
    String lone = group(alice, "Lone");
    expense(alice, shared, alice.user().id(), bob.user().id(), null);
    payment(bob, shared, bob.user().id(), alice.user().id());
    UUID op = UUID.randomUUID();
    var bobExpense = expense(bob, shared, bob.user().id(), alice.user().id(), op);
    payment(alice, shared, alice.user().id(), bob.user().id());
    send(bob, jsonPost("/api/groups/" + shared + "/members", Map.of("email", "x" + email)), 404);
    // Bob and Carol bookmark Alice; Carol never opened a personal ledger with her.
    send(
        bob,
        jsonPost("/api/friends", Map.of("groupId", shared, "participantId", alice.user().id())),
        200);
    send(
        carol,
        jsonPost("/api/friends", Map.of("groupId", shared, "participantId", alice.user().id())),
        200);
    // Bob and Alice also share a settled personal ledger, so Bob's bookmark must survive.
    send(
        bob,
        jsonPost(
            "/api/friends/" + alice.user().id() + "/expenses",
            Map.of(
                "description",
                "Taxi",
                "currency",
                "USD",
                "amountMinor",
                500,
                "paidByUserId",
                alice.user().id(),
                "participantUserIds",
                List.of(alice.user().id(), bob.user().id()))),
        201);
    send(
        bob,
        jsonPost(
            "/api/friends/" + alice.user().id() + "/payments",
            Map.of(
                "currency",
                "USD",
                "amountMinor",
                250,
                "payerUserId",
                bob.user().id(),
                "payeeUserId",
                alice.user().id())),
        201);
    var cookie =
        mockMvc
            .perform(jsonPost("/api/auth/login", new LoginRequest(email, "password123")))
            .andReturn()
            .getResponse()
            .getCookie("__Host-spliteasy-refresh");
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    String resetToken = mailbox.token(email);

    close(alice, "password123", 204);

    // Credentials: old access, refresh, login and reset are all dead.
    send(alice, get("/api/me"), 401);
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", Map.of())
                .cookie(cookie)
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, "password123")))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/password-resets",
                Map.of("token", resetToken, "password", "newPassword123")))
        .andExpect(status().isBadRequest());
    mailbox.messages.remove(email);
    mockMvc
        .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
        .andExpect(status().isAccepted());
    assertThat(mailbox.messages).doesNotContainKey(email);

    // Others still read the full ledger, with the closed account anonymised.
    bodies.clear();
    var detail = send(bob, get("/api/groups/" + shared), 200);
    assertThat(detail.path("createdBy").path("id").asText())
        .isNotEqualTo(alice.user().id().toString());
    send(bob, get("/api/groups/" + shared + "/balances"), 200);
    send(bob, get("/api/groups/" + shared + "/balances/obligations"), 200);
    send(bob, get("/api/groups/" + shared + "/expenses"), 200);
    var expenses = send(bob, get("/api/groups/" + shared + "/expenses"), 200);
    assertThat(expenses).hasSize(2);
    send(bob, get("/api/groups/" + shared + "/payments"), 200);
    send(bob, get("/api/groups/" + shared + "/expenses/" + bobExpense.path("id").asText()), 200);
    send(
        bob,
        get("/api/groups/" + shared + "/expenses/" + bobExpense.path("id").asText() + "/history"),
        200);
    send(bob, get("/api/groups/history"), 200);
    send(bob, get("/api/activity"), 200);
    send(bob, get("/api/dashboard"), 200);
    var friends = send(bob, get("/api/friends"), 200);
    assertThat(friends.get(0).path("displayName").asText()).isEqualTo("Closed account");
    // Carol's bookmark survives, still reads, and shows the closed account.
    assertThat(send(carol, get("/api/friends"), 200).get(0).path("displayName").asText())
        .isEqualTo("Closed account");
    send(carol, get("/api/friends/" + alice.user().id()), 200);
    // Replayed keyed receipt carries no old email either.
    send(
        bob,
        jsonPost(
            "/api/groups/" + shared + "/expenses",
            Map.of(
                "description",
                "Dinner",
                "currency",
                "USD",
                "amountMinor",
                1000,
                "paidByUserId",
                bob.user().id(),
                "participantUserIds",
                List.of(alice.user().id()),
                "operationId",
                op)),
        201);
    assertThat(String.join("\n", bodies))
        .doesNotContain(email)
        .doesNotContain("\"Alice\"")
        .contains("Closed account");

    // Group and friend consequences.
    assertThat(
            jdbc.queryForObject(
                "select deleted_at is not null from groups where id=?::uuid", Boolean.class, lone))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from group_memberships where user_id=?",
                Integer.class,
                alice.user().id()))
        .isZero();
    // A bookmark never opens a ledger with, or writes to one of, a closed account.
    send(
        carol,
        jsonPost(
            "/api/friends/" + alice.user().id() + "/expenses",
            Map.of(
                "description",
                "Coffee",
                "currency",
                "USD",
                "amountMinor",
                500,
                "paidByUserId",
                carol.user().id(),
                "participantUserIds",
                List.of(alice.user().id()))),
        403);
    var pay =
        Map.of(
            "currency",
            "USD",
            "amountMinor",
            100,
            "payerUserId",
            carol.user().id(),
            "payeeUserId",
            alice.user().id());
    send(carol, jsonPost("/api/friends/" + alice.user().id() + "/payments", pay), 403);
    var payBob =
        Map.of(
            "currency",
            "USD",
            "amountMinor",
            100,
            "payerUserId",
            bob.user().id(),
            "payeeUserId",
            alice.user().id());
    send(bob, jsonPost("/api/friends/" + alice.user().id() + "/payments", payBob), 403);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from personal_ledgers where account_low=?::uuid or account_high=?::uuid",
                Integer.class,
                carol.user().id().toString(),
                carol.user().id().toString()))
        .isZero();

    // The address is free: registration is a new empty account.
    var again = register(email, "password123", "Alice again");
    assertThat(again.user().id()).isNotEqualTo(alice.user().id());
    assertThat(send(again, get("/api/groups"), 200)).isEmpty();
    assertThat(send(again, get("/api/friends"), 200)).isEmpty();
  }

  @Test
  void anExpenseHoldingTheGroupLockMakesClosureWaitThenRefuseNamingTheGroup() throws Exception {
    var alice = register(UUID.randomUUID() + "@example.test", "password123", "Alice");
    var bob = register(UUID.randomUUID() + "@example.test", "password123", "Bob");
    String g = group(alice, "Locked trip", bob);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var writer = new java.util.concurrent.atomic.AtomicReference<Thread>();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    // The writer's second membership check runs after it has locked the group row.
    org.mockito.Mockito.doAnswer(
            call -> {
              call.callRealMethod();
              if (Thread.currentThread() == writer.get() && calls.incrementAndGet() == 2) {
                locked.countDown();
                assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              }
              return null;
            })
        .when(membershipGuard)
        .requireMember(
            org.mockito.ArgumentMatchers.eq(java.util.UUID.fromString(g)),
            org.mockito.ArgumentMatchers.eq(bob.user().id()));
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var write =
          pool.submit(
              () -> {
                writer.set(Thread.currentThread());
                expense(bob, g, alice.user().id(), bob.user().id(), null);
                return null;
              });
      assertThat(locked.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var closing =
          pool.submit(
              () -> {
                var result =
                    mockMvc
                        .perform(
                            jsonPost("/api/me/closure", Map.of("currentPassword", "password123"))
                                .header("Authorization", bearer(alice.accessToken())))
                        .andReturn();
                return result.getResponse();
              });
      Thread.sleep(1500);
      assertThat(closing.isDone()).as("closure waits for the group lock").isFalse();
      release.countDown();
      write.get(20, java.util.concurrent.TimeUnit.SECONDS);
      var response = closing.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(response.getStatus()).isEqualTo(409);
      assertThat(response.getContentAsString()).contains("Locked trip");
    }
  }
}
