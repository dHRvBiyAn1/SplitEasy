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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
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
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FriendSettlementIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  ParticipantInvitationIntegrationTest.Mailbox mailbox;

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

  private String group(AuthResponse a, AuthResponse b, String name) throws Exception {
    String group = send(a, jsonPost("/api/groups", Map.of("name", name)), 201).path("id").asText();
    send(a, jsonPost("/api/groups/" + group + "/members", Map.of("email", b.user().email())), 200);
    return group;
  }

  private void befriend(AuthResponse a, AuthResponse b, String group) throws Exception {
    for (var pair : List.of(List.of(a, b), List.of(b, a)))
      send(
          pair.get(0),
          jsonPost(
              "/api/friends", Map.of("groupId", group, "participantId", pair.get(1).user().id())),
          200);
  }

  private void expense(
      String url, AuthResponse payer, AuthResponse other, long amount, String currency)
      throws Exception {
    expense(url, payer, other.user().id().toString(), amount, currency);
  }

  private void expense(String url, AuthResponse payer, String other, long amount, String currency)
      throws Exception {
    send(
        payer,
        jsonPost(
            url,
            Map.of(
                "description",
                "Dinner",
                "currency",
                currency,
                "amountMinor",
                amount,
                "paidByUserId",
                payer.user().id(),
                "participantUserIds",
                List.of(payer.user().id().toString(), other))),
        201);
  }

  private String preview(AuthResponse friend, String currency) {
    return "/api/friends/" + friend.user().id() + "/settlement-preview?currency=" + currency;
  }

  private String settle(AuthResponse friend) {
    return "/api/friends/" + friend.user().id() + "/settlements";
  }

  private Map<String, Object> commit(JsonNode preview, UUID operationId) {
    var body = new HashMap<String, Object>();
    body.put("currency", preview.path("currency").asText());
    var allocations = new ArrayList<Object>();
    preview
        .path("allocations")
        .forEach(a -> allocations.add(objectMapper.convertValue(a, Map.class)));
    body.put("allocations", allocations);
    body.put("operationId", operationId);
    body.put("method", "CASH");
    body.put("note", "Settle all");
    return body;
  }

  private record World(AuthResponse alice, AuthResponse bob, String g1, String g2) {}

  /** G1: Bob owes Alice 500. G2: Alice owes Bob 200. Personal: Bob owes Alice 300. */
  private World world() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g1 = group(alice, bob, "Trip");
    String g2 = group(alice, bob, "Flat");
    befriend(alice, bob, g1);
    expense("/api/groups/" + g1 + "/expenses", alice, bob, 1000, "USD");
    expense("/api/groups/" + g2 + "/expenses", bob, alice, 400, "USD");
    expense("/api/friends/" + bob.user().id() + "/expenses", alice, bob, 600, "USD");
    return new World(alice, bob, g1, g2);
  }

  @Test
  void settlesEveryScopeInOneCurrencyAndReplaysExactly() throws Exception {
    var w = world();
    var preview = send(w.alice(), get(preview(w.bob(), "USD")), 200);
    assertThat(preview.path("allocations")).hasSize(3);
    assertThat(preview.path("netMinor").asLong()).isEqualTo(600);

    var op = UUID.randomUUID();
    var done = send(w.alice(), jsonPost(settle(w.bob()), commit(preview, op)), 201);
    assertThat(done.path("allocations")).hasSize(3);
    done.path("allocations").forEach(a -> assertThat(a.path("paymentId").asText()).isNotEmpty());

    var detail = send(w.alice(), get("/api/friends/" + w.bob().user().id()), 200);
    assertThat(detail.path("hasOpenObligations").asBoolean()).isFalse();
    assertThat(send(w.bob(), get("/api/groups/" + w.g1() + "/payments"), 200)).hasSize(1);
    assertThat(send(w.bob(), get("/api/groups/" + w.g2() + "/payments"), 200)).hasSize(1);
    assertThat(send(w.alice(), get(preview(w.bob(), "USD")), 200).path("allocations")).isEmpty();

    var replay = send(w.alice(), jsonPost(settle(w.bob()), commit(preview, op)), 201);
    assertThat(replay).isEqualTo(done);
    assertThat(send(w.bob(), get("/api/groups/" + w.g1() + "/payments"), 200)).hasSize(1);

    var changed = commit(preview, op);
    changed.put("note", "Different");
    send(w.alice(), jsonPost(settle(w.bob()), changed), 409);
  }

  @Test
  void aClaimedGuestAliasIsSettledAndPreviewMatchesTheFriendDetail() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String path =
        "/api/groups/"
            + send(alice, jsonPost("/api/groups", Map.of("name", "Guests")), 201)
                .path("id")
                .asText();
    var guest =
        send(alice, jsonPost(path + "/participants", Map.of("displayName", "Bob guest")), 201);
    expense(path + "/expenses", alice, guest.path("id").asText(), 3000, "USD");
    send(
        alice,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", bob.user().email(), "participantId", guest.path("id").asText())),
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
    send(
        alice,
        jsonPost(
            "/api/friends",
            Map.of(
                "groupId",
                path.substring(path.lastIndexOf('/') + 1),
                "participantId",
                bob.user().id())),
        200);

    var detail = send(alice, get("/api/friends/" + bob.user().id()), 200);
    long total = detail.path("balances").get(0).path("netMinor").asLong();
    var preview = send(alice, get(preview(bob, "USD")), 200);
    assertThat(preview.path("allocations")).hasSize(1);
    assertThat(preview.path("netMinor").asLong()).isEqualTo(total).isEqualTo(1500);

    send(alice, jsonPost(settle(bob), commit(preview, UUID.randomUUID())), 201);
    var after = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(after.path("hasOpenObligations").asBoolean()).isFalse();
  }

  @Test
  void anAllocationAboveThePaymentCapIsRefusedWithNothingWritten() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob, "Big");
    befriend(alice, bob, g);
    for (int i = 0; i < 3; i++)
      expense("/api/groups/" + g + "/expenses", alice, bob, 1_000_000_000_000L, "USD");
    var preview = send(alice, get(preview(bob, "USD")), 200);
    assertThat(preview.path("allocations").get(0).path("amountMinor").asLong())
        .isGreaterThan(1_000_000_000_000L);
    send(alice, jsonPost(settle(bob), commit(preview, UUID.randomUUID())), 409);
    assertThat(send(bob, get("/api/groups/" + g + "/payments"), 200)).isEmpty();
  }

  @Test
  void staleAllocationsAreRejectedWithNothingWritten() throws Exception {
    var w = world();
    var preview = send(w.alice(), get(preview(w.bob(), "USD")), 200);
    expense("/api/groups/" + w.g1() + "/expenses", w.alice(), w.bob(), 200, "USD");
    send(w.alice(), jsonPost(settle(w.bob()), commit(preview, UUID.randomUUID())), 409);
    assertThat(send(w.bob(), get("/api/groups/" + w.g1() + "/payments"), 200)).isEmpty();
    assertThat(send(w.bob(), get("/api/groups/" + w.g2() + "/payments"), 200)).isEmpty();
  }

  @Test
  void archivedScopesAreSkippedNotSettled() throws Exception {
    var w = world();
    send(w.alice(), jsonPost("/api/groups/" + w.g2() + "/archive", Map.of()), 200);
    var preview = send(w.alice(), get(preview(w.bob(), "USD")), 200);
    assertThat(preview.path("allocations")).hasSize(2);
    assertThat(preview.path("skipped")).hasSize(1);
    assertThat(preview.path("skipped").get(0).path("scopeId").asText()).isEqualTo(w.g2());
    send(w.alice(), jsonPost(settle(w.bob()), commit(preview, UUID.randomUUID())), 201);
    assertThat(send(w.bob(), get("/api/groups/" + w.g2() + "/payments"), 200)).isEmpty();
  }

  @Test
  void currenciesNeverMixAndOutsidersAreRefused() throws Exception {
    var w = world();
    var carol = account("Carol");
    expense("/api/groups/" + w.g1() + "/expenses", w.alice(), w.bob(), 900, "JPY");
    var jpy = send(w.alice(), get(preview(w.bob(), "JPY")), 200);
    assertThat(jpy.path("allocations")).hasSize(1);
    assertThat(jpy.path("netMinor").asLong()).isEqualTo(450);
    send(w.alice(), get(preview(carol, "USD")), 403);
    send(carol, get(preview(w.bob(), "USD")), 403);
    send(carol, jsonPost(settle(w.bob()), commit(jpy, UUID.randomUUID())), 403);
  }

  @Test
  void concurrentBatchesOverTheSameGroupsFinishWithoutDeadlock() throws Exception {
    var w = world();
    var preview = send(w.alice(), get(preview(w.bob(), "USD")), 200);
    var a =
        CompletableFuture.supplyAsync(
            () -> codeOf(w.alice(), w.bob(), commit(preview, UUID.randomUUID())));
    var b =
        CompletableFuture.supplyAsync(
            () -> codeOf(w.bob(), w.alice(), commit(preview, UUID.randomUUID())));
    var codes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    assertThat(codes).containsExactlyInAnyOrder(201, 409);
    assertThat(codes).doesNotContain(500);
    assertThat(send(w.bob(), get("/api/groups/" + w.g1() + "/payments"), 200)).hasSize(1);
  }

  private int codeOf(AuthResponse actor, AuthResponse friend, Map<String, Object> body) {
    try {
      return mockMvc
          .perform(
              jsonPost(settle(friend), body)
                  .header("Authorization", bearer(actor.accessToken()))
                  .header("X-Ledger-Version", "2")
                  .header("X-Participant-Version", "1"))
          .andReturn()
          .getResponse()
          .getStatus();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
