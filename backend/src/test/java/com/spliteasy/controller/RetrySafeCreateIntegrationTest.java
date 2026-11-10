package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(
    properties = "app.expenses.version-required-from=2026-11-19T00:00:00Z")
class RetrySafeCreateIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.test.context.bean.override.mockito.MockitoBean(name = "financialWriteClock")
  java.time.Clock clock;

  @org.junit.jupiter.api.BeforeEach
  void beforeDeadline() {
    org.mockito.Mockito.when(clock.instant())
        .thenReturn(java.time.Instant.parse("2026-10-19T00:00:00Z"));
  }

  @Test
  void lostGroupResponseReplaysOriginalAndChangedIntentConflicts() throws Exception {
    var actor = register("group-retry-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var key = UUID.randomUUID().toString();
    var body = Map.of("name", "Trip", "operationId", key);
    String first =
        mockMvc
            .perform(
                jsonPost("/api/groups", body).header("Authorization", bearer(actor.accessToken())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mockMvc
        .perform(jsonPost("/api/groups", body).header("Authorization", bearer(actor.accessToken())))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    mockMvc
        .perform(
            jsonPost("/api/groups", Map.of("name", "Changed", "operationId", key))
                .header("Authorization", bearer(actor.accessToken())))
        .andExpect(status().isConflict());
    var groups =
        mockMvc
            .perform(get("/api/groups").header("Authorization", bearer(actor.accessToken())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(groups).size()).isEqualTo(1);
  }

  @Test
  void expenseRetryRetainsOriginalResponseAfterEditAndDeletion() throws Exception {
    var actor =
        register("expense-retry-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var auth = bearer(actor.accessToken());
    var group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip"))
                            .header("Authorization", auth))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    var url = "/api/groups/" + group + "/expenses";
    var key = UUID.randomUUID().toString();
    var body =
        Map.of(
            "description",
            "Dinner",
            "amountCents",
            1200,
            "paidByUserId",
            actor.user().id(),
            "operationId",
            key);
    String first =
        mockMvc
            .perform(jsonPost(url, body).header("Authorization", auth))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var id = objectMapper.readTree(first).get("id").asText();
    mockMvc
        .perform(
            put(url + "/" + id)
                .header("Authorization", auth)
                .contentType("application/json")
                .content(
                    json(
                        Map.of(
                            "description",
                            "Changed",
                            "amountCents",
                            1500,
                            "paidByUserId",
                            actor.user().id()))))
        .andExpect(status().isOk());
    mockMvc
        .perform(jsonPost(url, body).header("Authorization", auth))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    mockMvc
        .perform(delete(url + "/" + id).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(jsonPost(url, body).header("Authorization", auth))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    mockMvc
        .perform(
            jsonPost(
                    url,
                    Map.of(
                        "description",
                        "Dinner",
                        "amountCents",
                        1300,
                        "paidByUserId",
                        actor.user().id(),
                        "operationId",
                        key))
                .header("Authorization", auth))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get(url).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void staleSeparateReadCannotOverwriteOrDeleteTheWinningExpense() throws Exception {
    var actor = register("stale-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var auth = bearer(actor.accessToken());
    var group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip"))
                            .header("Authorization", auth))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    var listUrl = "/api/groups/" + group + "/expenses";
    var created =
        objectMapper.readTree(
            mockMvc
                .perform(
                    jsonPost(
                            listUrl,
                            Map.of(
                                "description",
                                "Original",
                                "amountCents",
                                1200,
                                "paidByUserId",
                                actor.user().id()))
                        .header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var url = listUrl + "/" + created.get("id").asText();
    var staleVersion =
        objectMapper
            .readTree(
                mockMvc
                    .perform(get(url).header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("version")
            .asLong();
    var winning =
        Map.of(
            "description",
            "Winner",
            "amountCents",
            1500,
            "paidByUserId",
            actor.user().id(),
            "expectedVersion",
            staleVersion);
    var result =
        mockMvc
            .perform(
                put(url)
                    .header("Authorization", auth)
                    .contentType("application/json")
                    .content(json(winning)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mockMvc
        .perform(
            put(url)
                .header("Authorization", auth)
                .contentType("application/json")
                .content(
                    json(
                        Map.of(
                            "description",
                            "Loser",
                            "amountCents",
                            1800,
                            "paidByUserId",
                            actor.user().id(),
                            "expectedVersion",
                            staleVersion))))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            delete(url)
                .param("expectedVersion", Long.toString(staleVersion))
                .header("Authorization", auth))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get(url).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(content().json(result));
    var winnerVersion = objectMapper.readTree(result).get("version").asLong();
    assertThat(winnerVersion).isGreaterThan(staleVersion);
    mockMvc
        .perform(get(listUrl).header("Authorization", auth))
        .andExpect(jsonPath("$[0].version").value(winnerVersion));
    mockMvc
        .perform(
            delete(url)
                .param("expectedVersion", Long.toString(winnerVersion))
                .header("Authorization", auth))
        .andExpect(status().isNoContent());
  }

  @Test
  void legacyWritesExpireAtTheExactConfiguredUtcBoundary() throws Exception {
    var actor = register("deadline-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var auth = bearer(actor.accessToken());
    var group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip"))
                            .header("Authorization", auth))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    var listUrl = "/api/groups/" + group + "/expenses";
    var body =
        Map.of("description", "Original", "amountCents", 1200, "paidByUserId", actor.user().id());
    var created =
        objectMapper.readTree(
            mockMvc
                .perform(jsonPost(listUrl, body).header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var url = listUrl + "/" + created.get("id").asText();
    org.mockito.Mockito.when(clock.instant())
        .thenReturn(java.time.Instant.parse("2026-11-18T23:59:59.999999999Z"));
    var winner =
        mockMvc
            .perform(
                put(url)
                    .header("Authorization", auth)
                    .contentType("application/json")
                    .content(json(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(winner).get("version").asLong())
        .isGreaterThan(created.get("version").asLong());
    org.mockito.Mockito.when(clock.instant())
        .thenReturn(java.time.Instant.parse("2026-11-19T00:00:00Z"));
    mockMvc
        .perform(
            put(url)
                .header("Authorization", auth)
                .contentType("application/json")
                .content(json(body)))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expectedVersion")));
    mockMvc.perform(delete(url).header("Authorization", auth)).andExpect(status().isBadRequest());
    mockMvc.perform(get(url).header("Authorization", auth)).andExpect(content().json(winner));
    var fresh = new java.util.HashMap<String, Object>(body);
    fresh.put("expectedVersion", objectMapper.readTree(winner).get("version").asLong());
    var updated =
        mockMvc
            .perform(
                put(url)
                    .header("Authorization", auth)
                    .contentType("application/json")
                    .content(json(fresh)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mockMvc
        .perform(
            delete(url)
                .param("expectedVersion", objectMapper.readTree(updated).get("version").asText())
                .header("Authorization", auth))
        .andExpect(status().isNoContent());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void racedGroupClaimsReturnOneOriginalOrChangedIntentConflict(boolean changed) throws Exception {
    var actor = register("race-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var auth = bearer(actor.accessToken());
    var key = UUID.randomUUID().toString();
    var start = new java.util.concurrent.CyclicBarrier(2);
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                start.await();
                return mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip", "operationId", key))
                            .header("Authorization", auth))
                    .andReturn();
              });
      var second =
          threads.submit(
              () -> {
                start.await();
                return mockMvc
                    .perform(
                        jsonPost(
                                "/api/groups",
                                Map.of("name", changed ? "Changed" : "Trip", "operationId", key))
                            .header("Authorization", auth))
                    .andReturn();
              });
      var one = first.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse();
      var two = second.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse();
      assertThat(java.util.List.of(one.getStatus(), two.getStatus()))
          .containsExactlyInAnyOrder(201, changed ? 409 : 201);
      if (!changed)
        assertThat(objectMapper.readTree(one.getContentAsString()))
            .isEqualTo(objectMapper.readTree(two.getContentAsString()));
    }
    mockMvc
        .perform(get("/api/groups").header("Authorization", auth))
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void createIdentitiesAreScopedNormalizedAndNeverRestoreDeletedGroups() throws Exception {
    var actor = register("scope-a-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var other = register("scope-b-" + UUID.randomUUID() + "@test.com", "password123", "Other");
    var auth = bearer(actor.accessToken());
    var key = UUID.randomUUID().toString();
    var first =
        mockMvc
            .perform(
                jsonPost("/api/groups", Map.of("name", "  Trip  ", "operationId", key))
                    .header("Authorization", auth))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var group = objectMapper.readTree(first).get("id").asText();
    mockMvc
        .perform(
            jsonPost("/api/groups", Map.of("name", "Trip", "type", "OTHER", "operationId", key))
                .header("Authorization", auth))
        .andExpect(status().isCreated())
        .andExpect(content().json(first));
    var otherGroup =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip", "operationId", key))
                            .header("Authorization", bearer(other.accessToken())))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    assertThat(otherGroup).isNotEqualTo(group);
    mockMvc
        .perform(
            jsonPost("/api/groups/" + group + "/members", Map.of("email", other.user().email()))
                .header("Authorization", auth))
        .andExpect(status().isOk());
    var url = "/api/groups/" + group + "/expenses";
    var request =
        new java.util.HashMap<String, Object>(
            Map.of(
                "description",
                " Dinner ",
                "amountCents",
                1200,
                "paidByUserId",
                actor.user().id(),
                "participantUserIds",
                java.util.List.of(other.user().id(), actor.user().id()),
                "operationId",
                key));
    var expense =
        mockMvc
            .perform(jsonPost(url, request).header("Authorization", auth))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    request.put("description", "Dinner");
    request.put("category", "OTHER");
    request.put("splitType", "EQUAL");
    request.put("participantUserIds", java.util.List.of(actor.user().id(), other.user().id()));
    mockMvc
        .perform(jsonPost(url, request).header("Authorization", auth))
        .andExpect(status().isCreated())
        .andExpect(content().json(expense));
    mockMvc
        .perform(jsonPost(url, request).header("Authorization", bearer(other.accessToken())))
        .andExpect(status().isCreated());
    mockMvc
        .perform(get(url).header("Authorization", auth))
        .andExpect(jsonPath("$.length()").value(2));
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + otherGroup + "/expenses",
                    Map.of(
                        "description",
                        "Dinner",
                        "amountCents",
                        1200,
                        "paidByUserId",
                        other.user().id(),
                        "operationId",
                        key))
                .header("Authorization", bearer(other.accessToken())))
        .andExpect(status().isCreated());
    mockMvc
        .perform(delete("/api/groups/" + group).header("Authorization", auth))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            jsonPost("/api/groups", Map.of("name", "Trip", "operationId", key))
                .header("Authorization", auth))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(jsonPost(url, request).header("Authorization", auth))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/api/groups").header("Authorization", auth))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void childOnlySplitIntentEditAdvancesVersionAndRejectsStaleIntentOverwrite() throws Exception {
    var actor =
        register("intent-version-" + UUID.randomUUID() + "@test.com", "password123", "Owner");
    var other = register("intent-other-" + UUID.randomUUID() + "@test.com", "password123", "Other");
    var auth = bearer(actor.accessToken());
    var group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", Map.of("name", "Trip"))
                            .header("Authorization", auth))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    mockMvc
        .perform(
            jsonPost("/api/groups/" + group + "/members", Map.of("email", other.user().email()))
                .header("Authorization", auth))
        .andExpect(status().isOk());
    var listUrl = "/api/groups/" + group + "/expenses";
    var body =
        new java.util.HashMap<String, Object>(
            Map.of(
                "description",
                "Original",
                "amountCents",
                1200,
                "paidByUserId",
                actor.user().id(),
                "splitType",
                "WEIGHTS",
                "splits",
                java.util.List.of(
                    Map.of("userId", actor.user().id(), "value", 1),
                    Map.of("userId", other.user().id(), "value", 2))));
    var original =
        objectMapper.readTree(
            mockMvc
                .perform(jsonPost(listUrl, body).header("Authorization", auth))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var url = listUrl + "/" + original.get("id").asText();
    body.put("expectedVersion", original.get("version").asLong());
    body.put(
        "splits",
        java.util.List.of(
            Map.of("userId", actor.user().id(), "value", 2),
            Map.of("userId", other.user().id(), "value", 4)));
    var winner =
        objectMapper.readTree(
            mockMvc
                .perform(
                    put(url)
                        .header("Authorization", auth)
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(winner.get("participants")).isEqualTo(original.get("participants"));
    assertThat(winner.get("version").asLong()).isGreaterThan(original.get("version").asLong());
    body.put(
        "splits",
        java.util.List.of(
            Map.of("userId", actor.user().id(), "value", 3),
            Map.of("userId", other.user().id(), "value", 6)));
    mockMvc
        .perform(
            put(url)
                .header("Authorization", auth)
                .contentType("application/json")
                .content(json(body)))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get(url).header("Authorization", auth))
        .andExpect(content().json(winner.toString()));
  }
}
