package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** H05: bounded cursor history over expenses, settlements and refunds, plus CSV export. */
@AutoConfigureMockMvc
class HistoryIntegrationTest extends AbstractIntegrationTest {

  record Fixture(AuthResponse alice, AuthResponse bob, AuthResponse carol, String group) {}

  Fixture fixture(String name) throws Exception {
    String suffix = UUID.randomUUID().toString();
    var a = register("ha-" + suffix + "@example.com", "password123", "Alice");
    var b = register("hb-" + suffix + "@example.com", "password123", "Bob");
    var c = register("hc-" + suffix + "@example.com", "password123", "Carol");
    String group = group(a, name);
    for (var member : List.of(b, c)) add(a, group, member);
    return new Fixture(a, b, c, group);
  }

  String group(AuthResponse owner, String name) throws Exception {
    MvcResult g =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest(name))
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(g.getResponse().getContentAsString()).get("id").asText();
  }

  void add(AuthResponse owner, String group, AuthResponse member) throws Exception {
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + group + "/members",
                    new AddMemberRequest(member.user().email()))
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
        .andExpect(status().isOk());
  }

  String expense(
      AuthResponse payer, String group, String description, long amount, String currency, String on)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("description", description);
    body.put("amountMinor", amount);
    body.put("currency", currency);
    body.put("paidByUserId", payer.user().id());
    body.put("splitType", "EQUAL");
    body.put("spentOn", on);
    return created(payer, "/api/groups/" + group + "/expenses", body);
  }

  String payment(AuthResponse payer, AuthResponse payee, String group, long amount, String on)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payerUserId", payer.user().id());
    body.put("payeeUserId", payee.user().id());
    body.put("amountMinor", amount);
    body.put("currency", "USD");
    body.put("paidOn", on);
    return created(payer, "/api/groups/" + group + "/payments", body);
  }

  String refund(AuthResponse by, String group, String expense, long amount, String on)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amountMinor", amount);
    body.put("currency", "USD");
    body.put("refundedOn", on);
    return created(by, "/api/groups/" + group + "/expenses/" + expense + "/refunds", body);
  }

  String created(AuthResponse who, String path, Object body) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                jsonPost(path, body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()))
                    .header("X-Ledger-Version", "2"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
  }

  MvcResult history(AuthResponse who, Map<String, String> params) throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/groups/history")
            .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()))
            .header("X-Ledger-Version", "2");
    params.forEach(request::param);
    return mockMvc.perform(request).andReturn();
  }

  JsonNode page(AuthResponse who, Map<String, String> params) throws Exception {
    MvcResult r = history(who, params);
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    return objectMapper.readTree(r.getResponse().getContentAsString());
  }

  List<String> ids(JsonNode page) {
    List<String> ids = new ArrayList<>();
    page.get("items").forEach(i -> ids.add(i.get("id").asText()));
    return ids;
  }

  void deleteExpense(AuthResponse who, String group, String expense) throws Exception {
    var current =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/groups/" + group + "/expenses/" + expense)
                        .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken())))
                .andReturn()
                .getResponse()
                .getContentAsString());
    mockMvc
        .perform(
            delete("/api/groups/" + group + "/expenses/" + expense)
                .param("expectedVersion", current.get("version").asText())
                .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken())))
        .andExpect(status().isNoContent());
  }

  @Test
  void groupHistoryListsActiveExpensesSettlementsAndRefundsByFinancialDateNewestFirst()
      throws Exception {
    var f = fixture("Trip");
    String dinner = expense(f.alice(), f.group(), "Dinner", 9000, "USD", "2026-03-01");
    String taxi = expense(f.bob(), f.group(), "Taxi", 3000, "USD", "2026-03-04");
    String settle = payment(f.bob(), f.alice(), f.group(), 1000, "2026-03-03");
    String back = refund(f.alice(), f.group(), dinner, 900, "2026-03-02");
    String gone = expense(f.carol(), f.group(), "Mistake", 500, "USD", "2026-03-05");
    deleteExpense(f.carol(), f.group(), gone);

    JsonNode page = page(f.alice(), Map.of("groupId", f.group()));

    assertThat(ids(page)).containsExactly(taxi, settle, back, dinner);
    assertThat(page.get("nextCursor").isNull()).isTrue();
    JsonNode first = page.get("items").get(0);
    assertThat(first.get("type").asText()).isEqualTo("EXPENSE");
    assertThat(first.get("description").asText()).isEqualTo("Taxi");
    assertThat(first.get("financialDate").asText()).isEqualTo("2026-03-04");
    assertThat(first.get("currency").asText()).isEqualTo("USD");
    assertThat(first.get("amountMinor").asLong()).isEqualTo(3000);
    assertThat(first.get("groupId").asText()).isEqualTo(f.group());
    assertThat(first.get("groupName").asText()).isEqualTo("Trip");
    assertThat(first.hasNonNull("updatedAt")).isTrue();
    assertThat(page.get("items").get(1).get("type").asText()).isEqualTo("PAYMENT");
    assertThat(page.get("items").get(2).get("type").asText()).isEqualTo("REFUND");
    assertThat(page.get("items").get(2).get("amountMinor").asLong()).isEqualTo(900);
  }

  @Test
  void cursorWalksEntriesSharingOneFinancialDateWithoutGapsOrDuplicates() throws Exception {
    var f = fixture("Same day");
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      expected.add(expense(f.alice(), f.group(), "Item " + i, 300, "USD", "2026-04-01"));
    expected.add(payment(f.bob(), f.alice(), f.group(), 100, "2026-04-01"));
    // PostgreSQL orders uuid by unsigned bytes: lexical order of the lowercase hex form.
    expected.sort(java.util.Comparator.reverseOrder());

    List<String> walked = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Map<String, String> params = new LinkedHashMap<>(Map.of("groupId", f.group(), "limit", "2"));
      if (cursor != null) params.put("cursor", cursor);
      JsonNode page = page(f.alice(), params);
      assertThat(page.get("items").size()).isLessThanOrEqualTo(2);
      walked.addAll(ids(page));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
      pages++;
    } while (cursor != null);

    assertThat(walked).containsExactlyElementsOf(expected);
    assertThat(pages).isEqualTo(3);
  }

  @Test
  void historyNeverExposesGroupsTheViewerIsNotACurrentMemberOf() throws Exception {
    var f = fixture("Private");
    String mine = expense(f.alice(), f.group(), "Ours", 900, "USD", "2026-05-01");
    var outsider = register("ho-" + UUID.randomUUID() + "@example.com", "password123", "Olive");
    String theirs = group(outsider, "Elsewhere");
    String other = expense(outsider, theirs, "Theirs", 100, "USD", "2026-05-02");

    assertThat(history(outsider, Map.of("groupId", f.group())).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(
            history(outsider, Map.of("groupId", UUID.randomUUID().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(ids(page(outsider, Map.of()))).containsExactly(other);
    assertThat(ids(page(f.alice(), Map.of()))).containsExactly(mine);
    assertThat(
            history(f.alice(), Map.of("friendId", outsider.user().id().toString()))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Test
  void financialDateBoundsAreInclusiveCalendarDatesAndUpdatedBoundsUseRevisionTime()
      throws Exception {
    var f = fixture("Filters");
    expense(f.alice(), f.group(), "Before", 100, "USD", "2026-06-30");
    String first = expense(f.alice(), f.group(), "First day", 100, "USD", "2026-07-01");
    String last = payment(f.bob(), f.alice(), f.group(), 100, "2026-07-31");
    expense(f.alice(), f.group(), "After", 100, "USD", "2026-08-01");

    JsonNode july =
        page(f.alice(), Map.of("groupId", f.group(), "from", "2026-07-01", "to", "2026-07-31"));
    assertThat(ids(july)).containsExactly(last, first);

    java.time.Instant cut = java.time.Instant.now();
    String later = expense(f.carol(), f.group(), "Later", 100, "USD", "2026-01-01");
    assertThat(ids(page(f.alice(), Map.of("groupId", f.group(), "updatedAfter", cut.toString()))))
        .containsExactly(later);
    assertThat(ids(page(f.alice(), Map.of("groupId", f.group(), "updatedBefore", cut.toString()))))
        .hasSize(4)
        .doesNotContain(later);
    assertThat(
            history(f.alice(), Map.of("from", "2026-07-02", "to", "2026-07-01"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void friendScopeListsOnlyEntriesBothPeopleTakePartIn() throws Exception {
    var f = fixture("Friends");
    String shared = expense(f.alice(), f.group(), "Shared", 900, "USD", "2026-09-01");
    String paid = payment(f.bob(), f.alice(), f.group(), 300, "2026-09-02");
    payment(f.carol(), f.bob(), f.group(), 300, "2026-09-03");
    mockMvc
        .perform(
            jsonPost(
                    "/api/friends",
                    new com.spliteasy.dto.friend.SaveFriendRequest(
                        UUID.fromString(f.group()), f.bob().user().id()))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken()))
                .header("X-Ledger-Version", "2")
                .header("X-Participant-Version", "1"))
        .andExpect(status().is2xxSuccessful());

    JsonNode page = page(f.alice(), Map.of("friendId", f.bob().user().id().toString()));

    assertThat(ids(page)).containsExactly(paid, shared);
  }

  List<Map<String, String>> csv(AuthResponse who, Map<String, String> params, int status)
      throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/groups/history/export")
            .header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken()));
    params.forEach(request::param);
    MvcResult r = mockMvc.perform(request).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(status);
    if (status != 200) return List.of();
    assertThat(r.getResponse().getContentType()).startsWith("text/csv");
    assertThat(r.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment");
    List<List<String>> rows = parseCsv(r.getResponse().getContentAsString());
    List<Map<String, String>> records = new ArrayList<>();
    for (List<String> row : rows.subList(1, rows.size())) {
      Map<String, String> record = new LinkedHashMap<>();
      for (int i = 0; i < row.size(); i++) record.put(rows.get(0).get(i), row.get(i));
      records.add(record);
    }
    return records;
  }

  /** Minimal RFC 4180 reader: quoted fields, doubled quotes, CRLF rows. */
  static List<List<String>> parseCsv(String text) {
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') quoted = false;
        else field.append(c);
      } else if (c == '"') quoted = true;
      else if (c == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (c == '\n') {
        row.add(field.toString());
        field.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else if (c != '\r') field.append(c);
    }
    return rows;
  }

  @Test
  void csvExportHasPerPersonDeltasThatReconcileByCurrencyAndSafeText() throws Exception {
    var f = fixture("=Trip");
    String dinner = expense(f.alice(), f.group(), "=HYPERLINK(\"x\")", 9000, "USD", "2026-03-01");
    String settle = payment(f.bob(), f.alice(), f.group(), 1000, "2026-03-03");
    String back = refund(f.alice(), f.group(), dinner, 900, "2026-03-02");
    String kwd = expense(f.bob(), f.group(), "Souk, \"big\"", 1000, "KWD", "2026-03-04");
    String alice = f.alice().user().id().toString();
    String bob = f.bob().user().id().toString();

    var rows = csv(f.alice(), Map.of("groupId", f.group()), 200);

    // One row per participant per entry: 3 + 2 + 3 + 3.
    assertThat(rows).hasSize(11);
    assertThat(rows.get(0).get("transaction_id")).isEqualTo(kwd);
    assertThat(rows.get(0).get("amount")).isEqualTo("1.000");
    assertThat(rows.get(0).get("description")).isEqualTo("Souk, \"big\"");
    var dinnerRows = rows.stream().filter(r -> r.get("transaction_id").equals(dinner)).toList();
    assertThat(dinnerRows).hasSize(3);
    assertThat(dinnerRows.get(0).get("description")).isEqualTo("'=HYPERLINK(\"x\")");
    assertThat(dinnerRows.get(0).get("group_name")).isEqualTo("'=Trip");
    assertThat(dinnerRows.get(0).get("type")).isEqualTo("EXPENSE");
    assertThat(dinnerRows.get(0).get("financial_date")).isEqualTo("2026-03-01");
    var aliceDinner =
        dinnerRows.stream().filter(r -> r.get("participant_id").equals(alice)).findFirst().get();
    assertThat(aliceDinner)
        .containsEntry("participant_name", "Alice")
        .containsEntry("paid_minor", "9000")
        .containsEntry("owed_minor", "3000")
        .containsEntry("settlement_minor", "0")
        .containsEntry("net_minor", "6000")
        .containsEntry("net", "60.00")
        .containsEntry("currency", "USD")
        .containsEntry("amount_minor", "9000")
        .containsEntry("amount", "90.00");
    assertThat(aliceDinner.get("last_changed_by")).isEqualTo("Alice");
    var bobSettle =
        rows.stream()
            .filter(r -> r.get("transaction_id").equals(settle))
            .filter(r -> r.get("participant_id").equals(bob))
            .findFirst()
            .get();
    assertThat(bobSettle)
        .containsEntry("settlement_minor", "1000")
        .containsEntry("net_minor", "1000");
    var aliceRefund =
        rows.stream()
            .filter(r -> r.get("transaction_id").equals(back))
            .filter(r -> r.get("participant_id").equals(alice))
            .findFirst()
            .get();
    assertThat(aliceRefund)
        .containsEntry("type", "REFUND")
        .containsEntry("paid_minor", "-900")
        .containsEntry("owed_minor", "-300")
        .containsEntry("net_minor", "-600");

    // Totals reconcile per currency: nets sum to zero; distinct ids recover each entry total once.
    Map<String, Long> nets = new LinkedHashMap<>();
    Map<String, Long> totals = new LinkedHashMap<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (var r : rows) {
      nets.merge(r.get("currency"), Long.parseLong(r.get("net_minor")), Long::sum);
      if (seen.add(r.get("transaction_id")) && r.get("type").equals("EXPENSE"))
        totals.merge(r.get("currency"), Long.parseLong(r.get("amount_minor")), Long::sum);
    }
    assertThat(nets).containsEntry("USD", 0L).containsEntry("KWD", 0L);
    assertThat(totals).containsEntry("USD", 9000L).containsEntry("KWD", 1000L);

    var outsider = register("hx-" + UUID.randomUUID() + "@example.com", "password123", "Olive");
    csv(outsider, Map.of("groupId", f.group()), 403);
    assertThat(csv(outsider, Map.of(), 200)).isEmpty();
  }

  @Test
  void csvKeepsThePayerRowWhenThePayerIsNotInTheSplit() throws Exception {
    var f = fixture("Treat");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("description", "Treat");
    body.put("amountMinor", 1000);
    body.put("currency", "USD");
    body.put("paidByUserId", f.alice().user().id());
    body.put("splitType", "EQUAL");
    body.put("participantUserIds", List.of(f.bob().user().id(), f.carol().user().id()));
    body.put("spentOn", "2026-03-01");
    created(f.alice(), "/api/groups/" + f.group() + "/expenses", body);

    var rows = csv(f.alice(), Map.of("groupId", f.group()), 200);

    assertThat(rows).hasSize(3);
    assertThat(rows.stream().mapToLong(r -> Long.parseLong(r.get("net_minor"))).sum()).isZero();
    assertThat(rows.stream().mapToLong(r -> Long.parseLong(r.get("paid_minor"))).sum())
        .isEqualTo(1000);
  }

  @Test
  void pageSizeIsBoundedAndMalformedCursorsAreRejected() throws Exception {
    var f = fixture("Bounds");
    for (String limit : List.of("0", "101", "-1"))
      assertThat(history(f.alice(), Map.of("limit", limit)).getResponse().getStatus())
          .isEqualTo(400);
    assertThat(history(f.alice(), Map.of("cursor", "not-a-cursor")).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(page(f.alice(), Map.of("limit", "100")).get("items").isArray()).isTrue();
  }
}
