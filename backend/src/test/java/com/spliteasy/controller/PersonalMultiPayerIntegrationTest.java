package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** E03 × G02.2: a personal expense that both friends paid towards reconciles end to end. */
@AutoConfigureMockMvc
class PersonalMultiPayerIntegrationTest extends AbstractIntegrationTest {

  private JsonNode send(AuthResponse who, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(who.accessToken()))
                    .header("X-Ledger-Version", "2")
                    .header("X-Participant-Version", "1"))
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void bothFriendsContributingReconcilesInFriendDetailAndDashboard() throws Exception {
    var alice = register(UUID.randomUUID() + "@example.test", "password123", "Alice");
    var bob = register(UUID.randomUUID() + "@example.test", "password123", "Bob");
    String group =
        send(alice, jsonPost("/api/groups", Map.of("name", "Shared")), 201).path("id").asText();
    send(
        alice,
        jsonPost("/api/groups/" + group + "/members", Map.of("email", bob.user().email())),
        200);
    for (var pair : List.of(List.of(alice, bob), List.of(bob, alice)))
      send(
          pair.get(0),
          jsonPost(
              "/api/friends", Map.of("groupId", group, "participantId", pair.get(1).user().id())),
          200);

    // 1000 split equally; Alice paid 700, Bob 300 -> Alice +200, Bob -200.
    send(
        alice,
        jsonPost(
            "/api/friends/" + bob.user().id() + "/expenses",
            Map.of(
                "description",
                "Dinner",
                "currency",
                "USD",
                "amountMinor",
                1000,
                "paidByUserId",
                alice.user().id(),
                "participantUserIds",
                List.of(alice.user().id(), bob.user().id()),
                "contributions",
                List.of(
                    Map.of("userId", alice.user().id(), "amountMinor", 700),
                    Map.of("userId", bob.user().id(), "amountMinor", 300)))),
        201);

    for (var row : List.of(List.of(alice, bob, 200L), List.of(bob, alice, -200L))) {
      var viewer = (AuthResponse) row.get(0);
      var other = (AuthResponse) row.get(1);
      long net = (Long) row.get(2);
      var detail = send(viewer, get("/api/friends/" + other.user().id()), 200);
      assertThat(detail.path("personalBalances").get(0).path("netMinor").asLong()).isEqualTo(net);
      var dashboard = send(viewer, get("/api/dashboard"), 200);
      assertThat(dashboard.path("totals").get(0).path("totalNetMinor").asLong()).isEqualTo(net);
      assertThat(dashboard.path("people").get(0).path("netMinor").asLong()).isEqualTo(net);
    }
  }
}
