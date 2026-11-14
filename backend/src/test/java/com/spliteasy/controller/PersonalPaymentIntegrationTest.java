package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PersonalPaymentIntegrationTest extends AbstractIntegrationTest {

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

  private void friends(AuthResponse a, AuthResponse b) throws Exception {
    String group =
        send(a, jsonPost("/api/groups", Map.of("name", "Shared")), 201).path("id").asText();
    send(a, jsonPost("/api/groups/" + group + "/members", Map.of("email", b.user().email())), 200);
    for (var pair : List.of(List.of(a, b), List.of(b, a))) {
      send(
          pair.get(0),
          jsonPost(
              "/api/friends", Map.of("groupId", group, "participantId", pair.get(1).user().id())),
          200);
    }
  }

  private MockHttpServletRequestBuilder jsonPut(String url, Object body) throws Exception {
    return put(url).contentType("application/json").content(objectMapper.writeValueAsString(body));
  }

  private String payments(AuthResponse friend) {
    return "/api/friends/" + friend.user().id() + "/payments";
  }

  private Map<String, Object> payment(
      AuthResponse payer, AuthResponse payee, long amount, String currency, UUID operationId) {
    var body = new java.util.HashMap<String, Object>();
    body.put("payerUserId", payer.user().id());
    body.put("payeeUserId", payee.user().id());
    body.put("amountMinor", amount);
    body.put("currency", currency);
    body.put("method", "CASH");
    body.put("note", "Lunch");
    if (operationId != null) body.put("operationId", operationId);
    return body;
  }

  private void expense(AuthResponse payer, AuthResponse friend, long amount) throws Exception {
    send(
        payer,
        jsonPost(
            "/api/friends/" + friend.user().id() + "/expenses",
            Map.of(
                "description",
                "Dinner",
                "currency",
                "USD",
                "amountMinor",
                amount,
                "paidByUserId",
                payer.user().id(),
                "participantUserIds",
                List.of(payer.user().id(), friend.user().id()))),
        201);
  }

  @Test
  void settlementBetweenFriendsReconcilesPersonalBalanceAndSupportsEitherDirection()
      throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    friends(alice, bob);
    expense(alice, bob, 1000); // Bob owes Alice 500

    var paid = send(bob, jsonPost(payments(alice), payment(bob, alice, 200, "USD", null)), 201);
    assertThat(paid.path("amountMinor").asLong()).isEqualTo(200);
    assertThat(paid.path("recordedBy").path("id").asText()).isEqualTo(bob.user().id().toString());

    var view = send(alice, get("/api/friends/" + bob.user().id()), 200);
    assertThat(view.path("personalBalances").get(0).path("netMinor").asLong()).isEqualTo(300);
    assertThat(send(alice, get(payments(bob)), 200)).hasSize(1);
    send(alice, get(payments(bob) + "/" + paid.path("id").asText()), 200);

    // the receiving side can record the same direction too (Alice records Bob's payment)
    send(alice, jsonPost(payments(bob), payment(bob, alice, 100, "USD", null)), 201);
    var after = send(bob, get("/api/friends/" + alice.user().id()), 200);
    assertThat(after.path("personalBalances").get(0).path("netMinor").asLong()).isEqualTo(-200);
  }

  @Test
  void onlyTheTwoFriendsCanBeThePair() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    friends(alice, bob);
    send(alice, jsonPost(payments(bob), payment(alice, carol, 100, "USD", null)), 400);
    send(alice, jsonPost(payments(bob), payment(carol, bob, 100, "USD", null)), 400);
    send(alice, jsonPost(payments(bob), payment(alice, alice, 100, "USD", null)), 400);
    // a third account can neither list nor record against the pair
    send(carol, get(payments(bob)), 403);
    send(carol, jsonPost(payments(bob), payment(carol, bob, 100, "USD", null)), 403);
  }

  @Test
  void keyedRetryReplaysAndUndoRestoreNeedExpectedVersion() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    friends(alice, bob);
    var op = UUID.randomUUID();
    var first = send(alice, jsonPost(payments(bob), payment(alice, bob, 700, "JPY", op)), 201);
    var replay = send(alice, jsonPost(payments(bob), payment(alice, bob, 700, "JPY", op)), 201);
    assertThat(replay.path("id")).isEqualTo(first.path("id"));
    send(alice, jsonPost(payments(bob), payment(alice, bob, 800, "JPY", op)), 409);
    assertThat(send(alice, get(payments(bob)), 200)).hasSize(1);

    String url = payments(bob) + "/" + first.path("id").asText();
    long version = first.path("version").asLong();
    String bobUrl = payments(alice) + "/" + first.path("id").asText();
    send(bob, delete(bobUrl + "?expectedVersion=" + (version + 5)), 409);
    send(bob, delete(bobUrl + "?expectedVersion=" + version), 204);
    assertThat(send(alice, get(payments(bob)), 200)).hasSize(0);
    var deleted = send(alice, get(payments(bob) + "/deleted"), 200);
    assertThat(deleted).hasSize(1);
    long deletedVersion = deleted.get(0).path("payment").path("version").asLong();
    send(alice, jsonPost(url + "/restore", Map.of("expectedVersion", version)), 409);
    send(alice, jsonPost(url + "/restore", Map.of("expectedVersion", deletedVersion)), 200);
    assertThat(send(alice, get(payments(bob)), 200)).hasSize(1);
    assertThat(send(alice, get(url + "/history"), 200).size()).isGreaterThanOrEqualTo(3);
  }

  @Test
  void correctionKeepsThePairAndLocksCurrency() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    friends(alice, bob);
    var created = send(alice, jsonPost(payments(bob), payment(alice, bob, 500, "USD", null)), 201);
    String url = payments(alice) + "/" + created.path("id").asText(); // Bob's view of the pair
    var fix = new java.util.HashMap<String, Object>(payment(bob, alice, 450, "USD", null));
    fix.put("paidOn", created.path("paidOn").asText());
    fix.put("expectedVersion", created.path("version").asLong());
    var corrected = send(bob, jsonPut(url, fix), 200);
    assertThat(corrected.path("amountMinor").asLong()).isEqualTo(450);
    assertThat(corrected.path("payer").path("id").asText()).isEqualTo(bob.user().id().toString());

    var third = new java.util.HashMap<String, Object>(fix);
    third.put("payeeUserId", carol.user().id());
    third.put("expectedVersion", corrected.path("version").asLong());
    send(bob, jsonPut(url, third), 400);
  }
}
