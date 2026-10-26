package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class InvitationDeliveryUnavailableIntegrationTest extends AbstractIntegrationTest {
  private JsonNode send(AuthResponse account, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(account.accessToken()))
                    .header("X-Ledger-Version", "2")
                    .header("X-Participant-Version", "1"))
            .andExpect(status().is(expected))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  @Test
  void unavailableMailStaysHonestWhileForwardedSingleUseLinksNeverClaimGuestDebt()
      throws Exception {
    String prefix = UUID.randomUUID().toString();
    var owner = register(prefix + "a@example.test", "password123", "Owner");
    var joining = register(prefix + "b@example.test", "password123", "Joining");
    var group = send(owner, jsonPost("/api/groups", Map.of("name", "Independent links")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    var guest =
        send(owner, jsonPost(path + "/participants", Map.of("displayName", "Joining")), 201);
    send(
        owner,
        jsonPost(
            path + "/expenses",
            Map.of(
                "description",
                "Before signup",
                "amountCents",
                800,
                "paidByUserId",
                owner.user().id())),
        201);
    send(
        owner,
        jsonPost(
            path + "/invitations/email",
            Map.of("email", joining.user().email(), "participantId", guest.path("id").asText())),
        503);
    assertThat(send(owner, get(path + "/invitations"), 200).size()).isZero();
    var link = send(owner, jsonPost(path + "/invitations/links", Map.of()), 201);
    String token = link.path("url").asText().split("&token=")[1];
    assertThat(send(owner, get(path + "/invitations"), 200).toString()).doesNotContain(token);
    var preview = send(joining, jsonPost("/api/invitations/preview", Map.of("token", token)), 200);
    assertThat(preview.path("requiresOwnershipConfirmation").asBoolean()).isFalse();
    assertThat(preview.path("participant").isNull()).isTrue();
    var accepted = send(joining, jsonPost("/api/invitations/accept", Map.of("token", token)), 200);
    assertThat(accepted.path("claimed").asBoolean()).isFalse();
    send(joining, jsonPost("/api/invitations/accept", Map.of("token", token)), 400);
    var joined = send(joining, get(path), 200);
    assertThat(joined.path("participants").size()).isEqualTo(3);
    assertThat(joined.path("ownedParticipantIds").toString())
        .doesNotContain(guest.path("id").asText());
    assertThat(send(joining, get(path + "/balances/mine"), 200).size()).isZero();
    send(owner, delete(path + "/invitations/" + link.path("invitation").path("id").asText()), 409);
    var revoked = send(owner, jsonPost(path + "/invitations/links", Map.of()), 201);
    send(
        owner,
        delete(path + "/invitations/" + revoked.path("invitation").path("id").asText()),
        204);
    send(
        joining,
        jsonPost(
            "/api/invitations/preview",
            Map.of("token", revoked.path("url").asText().split("&token=")[1])),
        400);
    send(joining, jsonPost("/api/invitations/preview", Map.of("token", "A".repeat(43))), 400);
  }
}
