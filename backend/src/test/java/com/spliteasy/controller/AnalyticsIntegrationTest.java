package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** P04: spending analytics must reconcile with the H05 CSV for the same filter. */
@AutoConfigureMockMvc
class AnalyticsIntegrationTest extends AbstractIntegrationTest {
  AuthResponse user(String name) throws Exception {
    return register(name + "-" + UUID.randomUUID() + "@example.com", "password123", name);
  }

  String group(AuthResponse owner, AuthResponse... members) throws Exception {
    String id =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", new CreateGroupRequest("Trip"))
                            .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    for (var m : members)
      mockMvc.perform(
          jsonPost("/api/groups/" + id + "/members", new AddMemberRequest(m.user().email()))
              .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())));
    return id;
  }

  Map<String, Object> body(
      AuthResponse payer, String desc, long amount, String currency, String category, String on) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("description", desc);
    body.put("amountMinor", amount);
    body.put("currency", currency);
    body.put("paidByUserId", payer.user().id());
    body.put("splitType", "EQUAL");
    body.put("category", category);
    body.put("spentOn", on);
    return body;
  }

  JsonNode post(AuthResponse who, String path, Object body) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                jsonPost(path, body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()))
                    .header("X-Ledger-Version", "2"))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return objectMapper.readTree(r.getResponse().getContentAsString());
  }

  JsonNode analytics(AuthResponse who, Map<String, String> params, int status) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/analytics")
            .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()))
            .header("X-Ledger-Version", "2");
    params.forEach(request::param);
    MvcResult r = mockMvc.perform(request).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(status);
    return status == 200 ? objectMapper.readTree(r.getResponse().getContentAsString()) : null;
  }

  long sum(JsonNode analytics, String currency, String field) {
    long total = 0;
    for (JsonNode row : analytics.get("rows"))
      if (row.get("currency").asText().equals(currency)) total += row.get(field).asLong();
    return total;
  }

  List<Map<String, String>> csv(AuthResponse who, Map<String, String> params) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/groups/history/export")
            .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()));
    params.forEach(request::param);
    var rows =
        HistoryIntegrationTest.parseCsv(
            mockMvc.perform(request).andReturn().getResponse().getContentAsString());
    List<Map<String, String>> records = new ArrayList<>();
    for (List<String> row : rows.subList(1, rows.size())) {
      Map<String, String> record = new LinkedHashMap<>();
      for (int i = 0; i < row.size(); i++) record.put(rows.get(0).get(i), row.get(i));
      records.add(record);
    }
    return records;
  }

  /** CSV side of the same numbers: distinct entry totals, and the viewer's own rows. */
  void assertReconciles(AuthResponse alice, JsonNode result, Map<String, String> filter)
      throws Exception {
    var rows = csv(alice, filter);
    for (String currency : List.of("USD", "KWD")) {
      long total = 0, paid = 0, owed = 0;
      var seen = new java.util.HashSet<String>();
      for (var r : rows) {
        if (!r.get("currency").equals(currency) || r.get("type").equals("PAYMENT")) continue;
        long sign = r.get("type").equals("REFUND") ? -1 : 1;
        if (seen.add(r.get("transaction_id")))
          total += sign * Long.parseLong(r.get("amount_minor"));
        if (r.get("participant_id").equals(alice.user().id().toString())) {
          paid += Long.parseLong(r.get("paid_minor"));
          owed += Long.parseLong(r.get("owed_minor"));
        }
      }
      assertThat(sum(result, currency, "groupTotalMinor")).as(currency + " total").isEqualTo(total);
      assertThat(sum(result, currency, "myPaidMinor")).as(currency + " paid").isEqualTo(paid);
      assertThat(sum(result, currency, "myShareMinor")).as(currency + " share").isEqualTo(owed);
    }
  }

  @Test
  void totalsReconcileWithCsvAfterEditRefundAndDeleteAndNeverMixCurrencies() throws Exception {
    var alice = user("Alice");
    var bob = user("Bob");
    var carol = user("Carol");
    String g = group(alice, bob, carol);
    String base = "/api/groups/" + g + "/expenses";
    var dinner = post(alice, base, body(alice, "Dinner", 9000, "USD", "FOOD_DRINK", "2026-03-01"));
    var taxi = post(bob, base, body(bob, "Taxi", 3000, "USD", "TRANSPORT", "2026-04-02"));
    post(bob, base, body(bob, "Souk", 1000, "KWD", "FOOD_DRINK", "2026-03-04"));
    var gone = post(carol, base, body(carol, "Oops", 777, "USD", "OTHER", "2026-03-05"));
    post(
        alice,
        base + "/" + dinner.get("id").asText() + "/refunds",
        Map.of("amountMinor", 900, "currency", "USD", "refundedOn", "2026-03-06"));
    var edit = body(bob, "Taxi", 3300, "USD", "TRANSPORT", "2026-04-02");
    edit.put("expectedVersion", taxi.get("version").asLong());
    mockMvc
        .perform(
            put(base + "/" + taxi.get("id").asText())
                .contentType("application/json")
                .content(json(edit))
                .header(HttpHeaders.AUTHORIZATION, bearer(bob.accessToken()))
                .header("X-Ledger-Version", "2"))
        .andReturn();
    mockMvc.perform(
        delete(base + "/" + gone.get("id").asText())
            .param("expectedVersion", gone.get("version").asText())
            .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())));

    for (String groupBy : List.of("category", "month", "person")) {
      var result = analytics(alice, Map.of("groupId", g, "groupBy", groupBy), 200);
      assertReconciles(alice, result, Map.of("groupId", g));
      assertThat(sum(result, "USD", "groupTotalMinor")).isEqualTo(9000 - 900 + 3300);
      assertThat(sum(result, "KWD", "groupTotalMinor")).isEqualTo(1000);
    }
    var byCategory = analytics(alice, Map.of("groupId", g, "groupBy", "category"), 200);
    assertThat(byCategory.get("rows").findValuesAsString("key"))
        .contains("FOOD_DRINK", "TRANSPORT");

    // Same filter on both sides: a date window drops the March entries entirely.
    var window = Map.of("groupId", g, "from", "2026-04-01", "to", "2026-04-30");
    var april =
        analytics(
            alice,
            Map.of("groupId", g, "groupBy", "month", "from", "2026-04-01", "to", "2026-04-30"),
            200);
    assertReconciles(alice, april, window);
    assertThat(sum(april, "USD", "groupTotalMinor")).isEqualTo(3300);
    assertThat(sum(april, "KWD", "groupTotalMinor")).isZero();

    // Without groupId every current group counts too.
    assertReconciles(alice, analytics(alice, Map.of("groupBy", "category"), 200), Map.of());
  }

  @Test
  void nonMemberIsForbiddenAndBadGroupByIsRejected() throws Exception {
    var alice = user("Alice");
    var mallory = user("Mallory");
    String g = group(alice);
    analytics(mallory, Map.of("groupId", g, "groupBy", "category"), 403);
    analytics(alice, Map.of("groupId", g, "groupBy", "colour"), 400);
  }
}
