package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class MultiPayerContributionIntegrationTest extends AbstractIntegrationTest {
  private AuthResponse a;
  private AuthResponse b;
  private AuthResponse c;
  private String groupUrl;

  @BeforeEach
  void threeMemberGroup() throws Exception {
    String suffix = UUID.randomUUID().toString();
    a = register("mp-a-" + suffix + "@example.com", "password123", "Ann");
    b = register("mp-b-" + suffix + "@example.com", "password123", "Ben");
    c = register("mp-c-" + suffix + "@example.com", "password123", "Cat");
    var created =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Multi payer"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    groupUrl =
        "/api/groups/"
            + objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    for (var member : List.of(b, c)) {
      mockMvc
          .perform(
              jsonPost(groupUrl + "/members", new AddMemberRequest(member.user().email()))
                  .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
          .andExpect(status().isOk());
    }
  }

  private UUID id(AuthResponse user) {
    return user.user().id();
  }

  private String contributions(Object... pairs) {
    StringBuilder json = new StringBuilder("[");
    for (int i = 0; i < pairs.length; i += 2) {
      if (i > 0) json.append(',');
      json.append("{\"userId\":\"")
          .append(id((AuthResponse) pairs[i]))
          .append("\",\"amountMinor\":")
          .append(pairs[i + 1])
          .append('}');
    }
    return json.append(']').toString();
  }

  private String expense(long amount, AuthResponse payer, String contributions, String extra) {
    return "{\"description\":\"Dinner\",\"amountMinor\":"
        + amount
        + ",\"currency\":\"USD\",\"paidByUserId\":\""
        + id(payer)
        + "\""
        + (contributions == null ? "" : ",\"contributions\":" + contributions)
        + (extra == null ? "" : "," + extra)
        + "}";
  }

  private JsonNode send(MockHttpServletRequestBuilder request, String body, ResultMatcher expected)
      throws Exception {
    String content =
        mockMvc
            .perform(
                request
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Ledger-Version", "2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken()))
                    .content(body))
            .andExpect(expected)
            .andReturn()
            .getResponse()
            .getContentAsString();
    return content.isEmpty() ? null : objectMapper.readTree(content);
  }

  private JsonNode create(String body) throws Exception {
    return send(post(groupUrl + "/expenses"), body, status().isCreated());
  }

  private JsonNode read(AuthResponse user, String path) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(
                get(groupUrl + path).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private Map<UUID, Long> nets() throws Exception {
    Map<UUID, Long> nets = new HashMap<>();
    for (JsonNode row : read(a, "/balances").path("balances"))
      nets.put(
          UUID.fromString(row.path("user").path("id").asText()), row.path("netMinor").asLong());
    return nets;
  }

  /** "from->to" display names → amount, from the raw direct obligations. */
  private Map<String, Long> obligations() throws Exception {
    Map<String, Long> debts = new HashMap<>();
    for (JsonNode row : read(a, "/balances/obligations"))
      debts.put(
          row.path("from").path("displayName").asText()
              + "->"
              + row.path("to").path("displayName").asText(),
          row.path("amountMinor").asLong());
    return debts;
  }

  @Test
  void unequalContributionsDriveNetsObligationsAndViewerDeltas() throws Exception {
    JsonNode created = create(expense(9000, a, contributions(a, 6000, b, 3000), null));

    assertThat(created.path("paidBy").path("id").asText()).isEqualTo(id(a).toString());
    assertThat(created.path("contributions")).hasSize(2);
    assertThat(created.path("contributions").get(0).path("user").path("displayName").asText())
        .isEqualTo("Ann");
    assertThat(created.path("contributions").get(0).path("amountMinor").asLong()).isEqualTo(6000);
    assertThat(created.path("contributions").get(1).path("amountMinor").asLong()).isEqualTo(3000);
    assertThat(nets())
        .containsEntry(id(a), 3000L)
        .containsEntry(id(b), 0L)
        .containsEntry(id(c), -3000L);
    assertThat(obligations()).isEqualTo(Map.of("Cat->Ann", 3000L));
    JsonNode summary = read(b, "/expenses").get(0);
    assertThat(summary.path("payerCount").asLong()).isEqualTo(2);
    assertThat(summary.path("viewerDeltaMinor").asLong()).isZero();
    assertThat(read(a, "/expenses").get(0).path("viewerDeltaMinor").asLong()).isEqualTo(3000);
  }

  private void assertReconciles() throws Exception {
    Map<UUID, Long> nets = nets();
    assertThat(nets.values().stream().mapToLong(Long::longValue).sum()).isZero();
    for (AuthResponse user : List.of(a, b, c)) {
      long pairwise = 0;
      for (JsonNode row : read(user, "/balances/mine")) pairwise += row.path("netMinor").asLong();
      assertThat(pairwise).isEqualTo(nets.getOrDefault(id(user), 0L));
    }
  }

  @Test
  void invalidContributionsAreRejectedBeforeAnyWrite() throws Exception {
    UUID outsider =
        register("mp-x-" + UUID.randomUUID() + "@example.com", "password123", "X").user().id();
    List<String> invalid =
        List.of(
            contributions(a, 5000, b, 3000),
            contributions(b, 9000),
            contributions(a, 9000, a, 0),
            contributions(a, 4500, a, 4500),
            "[]",
            "[{\"userId\":\""
                + outsider
                + "\",\"amountMinor\":4500},"
                + "{\"userId\":\""
                + id(a)
                + "\",\"amountMinor\":4500}]",
            "[{\"userId\":\""
                + id(a)
                + "\",\"amountMinor\":4500.5},"
                + "{\"userId\":\""
                + id(b)
                + "\",\"amountMinor\":4499.5}]");
    for (String body : invalid)
      send(post(groupUrl + "/expenses"), expense(9000, a, body, null), status().isBadRequest());
    assertThat(read(a, "/expenses")).isEmpty();
    assertThat(nets().values()).containsOnly(0L);
  }

  @Test
  void singlePayerRequestsReadAndBalanceExactlyAsBefore() throws Exception {
    JsonNode legacy = create(expense(9000, a, null, null));
    JsonNode explicit = create(expense(9000, a, contributions(a, 9000), null));
    for (JsonNode e : List.of(legacy, explicit)) {
      assertThat(e.path("contributions")).hasSize(1);
      assertThat(e.path("contributions").get(0).path("user").path("id").asText())
          .isEqualTo(id(a).toString());
      assertThat(e.path("contributions").get(0).path("amountMinor").asLong()).isEqualTo(9000);
    }
    assertThat(read(a, "/expenses").get(0).path("payerCount").asLong()).isEqualTo(1);
    assertThat(nets())
        .containsEntry(id(a), 12000L)
        .containsEntry(id(b), -6000L)
        .containsEntry(id(c), -6000L);
    assertThat(obligations()).isEqualTo(Map.of("Ben->Ann", 6000L, "Cat->Ann", 6000L));
    assertReconciles();
  }

  @Test
  void multipleCreditorsAndTinyAmountsReconcile() throws Exception {
    create(expense(9000, a, contributions(a, 5000, b, 4000), null));
    assertThat(obligations()).isEqualTo(Map.of("Cat->Ann", 2000L, "Cat->Ben", 1000L));
    assertReconciles();
    // 2 minor units over three people: equal shares 0/0/0, payer Ann absorbs both units.
    create(expense(2, a, contributions(a, 1, b, 1), null));
    assertThat(nets())
        .containsEntry(id(a), 1999L)
        .containsEntry(id(b), 1001L)
        .containsEntry(id(c), -3000L);
    assertReconciles();
  }

  @Test
  void editReplacesBothSidesAtomicallyAndLegacyEditCannotCollapsePayers() throws Exception {
    JsonNode created = create(expense(9000, a, contributions(a, 6000, b, 3000), null));
    String url = groupUrl + "/expenses/" + created.path("id").asText();
    long version = created.path("version").asLong();
    String legacy = expense(9000, a, null, "\"expectedVersion\":" + version);
    JsonNode refused = send(put(url), legacy, status().isConflict());
    assertThat(refused.toString()).contains("several payers");
    assertThat(nets()).containsEntry(id(b), 0L);

    send(
        put(url),
        expense(
            9000,
            a,
            contributions(a, 3000, c, 6000),
            "\"splitType\":\"UNEQUAL\",\"expectedVersion\":"
                + version
                + ",\"splits\":["
                + "{\"userId\":\""
                + id(a)
                + "\",\"value\":0},"
                + "{\"userId\":\""
                + id(b)
                + "\",\"value\":9000}]"),
        status().isOk());
    JsonNode detail = read(a, "/expenses/" + created.path("id").asText());
    assertThat(detail.path("contributions")).hasSize(2);
    assertThat(nets())
        .containsEntry(id(a), 3000L)
        .containsEntry(id(b), -9000L)
        .containsEntry(id(c), 6000L);
    assertThat(obligations()).isEqualTo(Map.of("Ben->Ann", 3000L, "Ben->Cat", 6000L));
    assertReconciles();

    send(
        put(url),
        expense(9000, a, null, "\"expectedVersion\":" + detail.path("version").asLong()),
        status().isConflict());
    send(
        put(url),
        expense(
            9000,
            a,
            contributions(a, 9000),
            "\"expectedVersion\":" + detail.path("version").asLong()),
        status().isOk());
    assertThat(read(a, "/expenses/" + created.path("id").asText()).path("contributions"))
        .hasSize(1);
    assertThat(nets()).containsEntry(id(a), 6000L);
  }

  @Test
  void deleteRestoreHistoryAndRefundReconcileForMultiPayerExpenses() throws Exception {
    JsonNode created = create(expense(9000, a, contributions(a, 6000, b, 3000), null));
    String url = groupUrl + "/expenses/" + created.path("id").asText();
    long version = created.path("version").asLong();

    send(delete(url).param("expectedVersion", "" + version), "", status().isNoContent());
    assertThat(nets().values()).allMatch(net -> net == 0L);
    assertThat(obligations()).isEmpty();

    JsonNode deleted = read(a, "/expenses/" + created.path("id").asText() + "/history");
    long deletedVersion = deleted.get(deleted.size() - 1).path("version").asLong();
    send(post(url + "/restore"), "{\"expectedVersion\":" + deletedVersion + "}", status().isOk());
    assertThat(nets()).containsEntry(id(a), 3000L).containsEntry(id(c), -3000L);
    assertThat(obligations()).isEqualTo(Map.of("Cat->Ann", 3000L));
    // Every revision snapshot, including the recorded contributions, survives the round trip.
    JsonNode history = read(a, "/expenses/" + created.path("id").asText() + "/history");
    assertThat(history.get(history.size() - 1).path("after").path("contributions")).hasSize(2);

    send(
        post(url + "/refunds"),
        "{\"amountMinor\":900,\"currency\":\"USD\",\"refundedOn\":\"2026-03-06\"}",
        status().isCreated());
    assertReconciles();
  }

  @Test
  void keyedRetryReplaysContributionsAndRejectsChangedOnes() throws Exception {
    String key = "\"operationId\":\"" + UUID.randomUUID() + "\"";
    JsonNode first = create(expense(9000, a, contributions(b, 3000, a, 6000), key));
    JsonNode replay = create(expense(9000, a, contributions(a, 6000, b, 3000), key));
    assertThat(replay).isEqualTo(first);
    send(
        post(groupUrl + "/expenses"),
        expense(9000, a, contributions(a, 5000, b, 4000), key),
        status().isConflict());
    assertThat(read(a, "/expenses")).hasSize(1);
  }

  @Test
  void formerContributorMustBeReaddedBeforeChangingTheExpense() throws Exception {
    JsonNode created =
        create(
            expense(
                9000,
                a,
                contributions(a, 4500, b, 4500),
                "\"splitType\":\"UNEQUAL\",\"splits\":["
                    + "{\"userId\":\""
                    + id(a)
                    + "\",\"value\":4500},"
                    + "{\"userId\":\""
                    + id(c)
                    + "\",\"value\":4500}]"));
    send(
        post(groupUrl + "/payments"),
        "{\"payerUserId\":\""
            + id(c)
            + "\",\"payeeUserId\":\""
            + id(b)
            + "\",\"amountMinor\":4500,\"currency\":\"USD\"}",
        status().isCreated());
    send(delete(groupUrl + "/members/" + id(b)), "", status().isNoContent());
    JsonNode refused =
        send(
            delete(
                groupUrl
                    + "/expenses/"
                    + created.path("id").asText()
                    + "?expectedVersion="
                    + created.path("version").asLong()),
            "",
            status().isConflict());
    assertThat(refused.toString()).contains("Re-add");
  }
}
