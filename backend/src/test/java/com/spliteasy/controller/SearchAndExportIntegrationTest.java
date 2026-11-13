package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** P01-A search and P07-A account export, through the public HTTP interface. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class SearchAndExportIntegrationTest extends AbstractIntegrationTest {
  @Autowired JdbcTemplate jdbc;

  record Fixture(AuthResponse alice, AuthResponse bob, String group) {}

  AuthResponse account(String name) throws Exception {
    return register(UUID.randomUUID() + "@example.test", "password123", name);
  }

  MvcResult call(AuthResponse who, MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(
            request
                .header("Authorization", bearer(who.accessToken()))
                .header("X-Ledger-Version", "2")
                .header("X-Participant-Version", "1"))
        .andReturn();
  }

  JsonNode send(AuthResponse who, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    MvcResult r = call(who, request);
    assertThat(r.getResponse().getStatus()).isEqualTo(expected);
    String body = r.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  Fixture fixture() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String group =
        send(alice, jsonPost("/api/groups", Map.of("name", "Trip")), 201).path("id").asText();
    send(
        alice,
        jsonPost("/api/groups/" + group + "/members", Map.of("email", bob.user().email())),
        200);
    return new Fixture(alice, bob, group);
  }

  String expense(
      Fixture f, String description, long amount, String currency, String category, String on)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("description", description);
    body.put("amountMinor", amount);
    body.put("currency", currency);
    body.put("paidByUserId", f.alice().user().id());
    body.put("splitType", "EQUAL");
    body.put("category", category);
    body.put("spentOn", on);
    return send(f.alice(), jsonPost("/api/groups/" + f.group() + "/expenses", body), 201)
        .path("id")
        .asText();
  }

  String payment(Fixture f, long amount, String note, String on) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("payerUserId", f.bob().user().id());
    body.put("payeeUserId", f.alice().user().id());
    body.put("amountMinor", amount);
    body.put("currency", "USD");
    body.put("paidOn", on);
    body.put("note", note);
    return send(f.bob(), jsonPost("/api/groups/" + f.group() + "/payments", body), 201)
        .path("id")
        .asText();
  }

  void deleteExpense(Fixture f, String expense) throws Exception {
    JsonNode current =
        send(f.alice(), get("/api/groups/" + f.group() + "/expenses/" + expense), 200);
    send(
        f.alice(),
        delete("/api/groups/" + f.group() + "/expenses/" + expense)
            .param("expectedVersion", current.path("version").asText()),
        204);
  }

  /** Alice saves Bob and records a personal (non-group) expense with him. */
  void personalExpense(Fixture f, String description) throws Exception {
    send(
        f.alice(),
        jsonPost(
            "/api/friends", Map.of("groupId", f.group(), "participantId", f.bob().user().id())),
        200);
    Map<String, Object> body = new HashMap<>();
    body.put("description", description);
    body.put("currency", "USD");
    body.put("amountMinor", 4000);
    body.put("paidByUserId", f.alice().user().id());
    body.put("participantUserIds", List.of(f.alice().user().id(), f.bob().user().id()));
    send(f.alice(), jsonPost("/api/friends/" + f.bob().user().id() + "/expenses", body), 201);
  }

  JsonNode search(AuthResponse who, int expected, String... kv) throws Exception {
    MockHttpServletRequestBuilder request = get("/api/search");
    for (int i = 0; i < kv.length; i += 2) request.param(kv[i], kv[i + 1]);
    return send(who, request, expected);
  }

  List<String> descriptions(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("items").forEach(i -> out.add(i.get("description").asText()));
    return out;
  }

  @Test
  void searchMatchesDescriptionNoteCategoryAndPersonNameAndSkipsDeletedEntries() throws Exception {
    var f = fixture();
    expense(f, "Sushi dinner", 9000, "USD", "FOOD_DRINK", "2026-03-01");
    expense(f, "Taxi", 3000, "USD", "TRANSPORT", "2026-03-02");
    String gone = expense(f, "Sushi mistake", 500, "USD", "FOOD_DRINK", "2026-03-03");
    payment(f, 1000, "thanks for the ramen", "2026-03-04");
    deleteExpense(f, gone);

    assertThat(descriptions(search(f.alice(), 200, "q", "SUSHI"))).containsExactly("Sushi dinner");
    assertThat(descriptions(search(f.alice(), 200, "q", "ramen"))).hasSize(1);
    assertThat(descriptions(search(f.alice(), 200, "q", "transport"))).containsExactly("Taxi");
    // A person name matches every live entry that person takes part in.
    assertThat(search(f.alice(), 200, "q", "Bob").path("items")).hasSize(3);
  }

  @Test
  void likeWildcardsAreLiteralText() throws Exception {
    var f = fixture();
    expense(f, "100% real", 100, "USD", "OTHER", "2026-03-01");
    expense(f, "100_x", 100, "USD", "OTHER", "2026-03-02");
    expense(f, "100 abc", 100, "USD", "OTHER", "2026-03-03");
    expense(f, "back\\slash", 100, "USD", "OTHER", "2026-03-04");

    assertThat(descriptions(search(f.alice(), 200, "q", "%"))).containsExactly("100% real");
    assertThat(descriptions(search(f.alice(), 200, "q", "_"))).containsExactly("100_x");
    assertThat(descriptions(search(f.alice(), 200, "q", "\\"))).containsExactly("back\\slash");
  }

  @Test
  void amountBoundsNeedACurrencyAndNeverCompareUnlikeCurrencies() throws Exception {
    var f = fixture();
    expense(f, "Dollars", 5000, "USD", "OTHER", "2026-03-01");
    expense(f, "Euros", 5000, "EUR", "OTHER", "2026-03-02");
    expense(f, "Yen", 90000, "JPY", "OTHER", "2026-03-03");

    search(f.alice(), 400, "minAmount", "1000");
    search(f.alice(), 400, "maxAmount", "1000");
    assertThat(descriptions(search(f.alice(), 200, "currency", "USD", "minAmount", "4000")))
        .containsExactly("Dollars");
    assertThat(descriptions(search(f.alice(), 200, "currency", "JPY", "minAmount", "4000")))
        .containsExactly("Yen");
    assertThat(search(f.alice(), 200, "currency", "USD", "minAmount", "6000").path("items"))
        .isEmpty();
    search(f.alice(), 400, "currency", "USD", "minAmount", "9000", "maxAmount", "100");
  }

  @Test
  void keysetPagingIsStableAcrossEntriesSharingADate() throws Exception {
    var f = fixture();
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < 5; i++)
      expected.add(expense(f, "Coffee " + i, 300, "USD", "FOOD_DRINK", "2026-04-01"));
    expected.sort(java.util.Comparator.reverseOrder());

    List<String> walked = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode page =
          cursor == null
              ? search(f.alice(), 200, "q", "coffee", "limit", "2")
              : search(f.alice(), 200, "q", "coffee", "limit", "2", "cursor", cursor);
      page.get("items").forEach(i -> walked.add(i.get("id").asText()));
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
    } while (cursor != null);

    assertThat(walked).containsExactlyElementsOf(expected);
    search(f.alice(), 400, "limit", "101");
    search(f.alice(), 400, "cursor", "not-a-cursor");
  }

  @Test
  void searchHonoursMembership() throws Exception {
    var f = fixture();
    var outsider = account("Outsider");
    expense(f, "Secret dinner", 900, "USD", "FOOD_DRINK", "2026-03-01");
    payment(f, 450, "settled", "2026-03-02");

    search(outsider, 403, "groupId", f.group());
    assertThat(search(outsider, 200, "q", "Secret").path("items")).isEmpty();

    // Bob settled his share, so he can leave: his results disappear and the group is forbidden.
    assertThat(search(f.bob(), 200, "q", "Secret").path("items")).hasSize(1);
    send(f.alice(), delete("/api/groups/" + f.group() + "/members/" + f.bob().user().id()), 204);
    assertThat(search(f.bob(), 200, "q", "Secret").path("items")).isEmpty();
    search(f.bob(), 403, "groupId", f.group());
  }

  @Test
  void personalLedgerEntriesAreSearchableButItsGroupIdIsNotAGroup() throws Exception {
    var f = fixture();
    personalExpense(f, "Concert tickets");
    String ledger =
        jdbc.queryForObject(
            "SELECT group_id::text FROM personal_ledgers WHERE account_low=?::uuid OR account_high=?::uuid",
            String.class,
            f.alice().user().id().toString(),
            f.alice().user().id().toString());

    assertThat(descriptions(search(f.alice(), 200, "q", "concert")))
        .containsExactly("Concert tickets");
    assertThat(
            descriptions(
                search(f.alice(), 200, "q", "concert", "friendId", f.bob().user().id().toString())))
        .containsExactly("Concert tickets");
    search(f.alice(), 404, "groupId", ledger);
    search(f.alice(), 404, "friendId", UUID.randomUUID().toString());
  }

  Map<String, String> unzip(MvcResult result) throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    try (var zip =
        new ZipInputStream(
            new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
      for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
        entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
    }
    return entries;
  }

  @Test
  void exportZipHoldsProfileGroupsAndOneCsvPerAuthorizedScope() throws Exception {
    var f = fixture();
    var outsider = account("Outsider");
    String other =
        send(outsider, jsonPost("/api/groups", Map.of("name", "Private")), 201).path("id").asText();
    expense(f, "Sushi dinner", 9000, "USD", "FOOD_DRINK", "2026-03-01");
    deleteExpense(f, expense(f, "Deleted lunch", 500, "USD", "FOOD_DRINK", "2026-03-02"));
    personalExpense(f, "Concert tickets");

    MvcResult r = call(f.alice(), get("/api/me/export"));

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(r.getResponse().getContentAsString()).isNotEmpty();
    assertThat(r.getResponse().getContentType()).isEqualTo("application/zip");
    assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment");
    Map<String, String> files = unzip(r);
    assertThat(files).containsKey("profile.json").containsKey("groups.json");
    JsonNode profile = objectMapper.readTree(files.get("profile.json"));
    assertThat(profile.path("email").asText()).isEqualTo(f.alice().user().email());
    assertThat(files.get("profile.json")).doesNotContain("password");
    List<String> groupIds = new ArrayList<>();
    objectMapper
        .readTree(files.get("groups.json"))
        .forEach(g -> groupIds.add(g.path("id").asText()));
    assertThat(groupIds).contains(f.group()).doesNotContain(other);
    assertThat(files.get("groups/" + f.group() + ".csv"))
        .contains("Sushi dinner")
        .doesNotContain("Deleted lunch")
        .doesNotContain("Concert");
    assertThat(
            files.entrySet().stream()
                .filter(e -> e.getKey().startsWith("personal/"))
                .map(Map.Entry::getValue))
        .anyMatch(csv -> csv.contains("Concert tickets"));
    assertThat(String.join("\n", files.values())).doesNotContain("Private");
    assertThat(unzip(call(outsider, get("/api/me/export"))).keySet())
        .noneMatch(k -> k.contains(f.group()));
  }

  @Test
  void exportRequiresAuthentication() throws Exception {
    assertThat(mockMvc.perform(get("/api/me/export")).andReturn().getResponse().getStatus())
        .isEqualTo(401);
  }
}
