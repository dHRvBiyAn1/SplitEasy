package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;

@AutoConfigureMockMvc
class CurrencyLedgerIntegrationTest extends AbstractIntegrationTest {
  private record Ledger(
      com.spliteasy.dto.auth.AuthResponse alice,
      com.spliteasy.dto.auth.AuthResponse bob,
      String groupId) {}

  private Ledger ledger(String prefix) throws Exception {
    var alice = register(prefix + "-a@example.test", "password123", "Alice");
    var bob = register(prefix + "-b@example.test", "password123", "Bob");
    var response =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Mixed"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    String id =
        objectMapper.readTree(response.getResponse().getContentAsString()).path("id").asText();
    mockMvc
        .perform(
            jsonPost("/api/groups/" + id + "/members", Map.of("email", bob.user().email()))
                .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
        .andExpect(status().isOk());
    return new Ledger(alice, bob, id);
  }

  private tools.jackson.databind.JsonNode read(Ledger ledger, String suffix) throws Exception {
    var result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/groups/" + ledger.groupId() + suffix)
                    .header("X-Ledger-Version", "2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private void expense(Ledger ledger, String currency, long amount, java.util.UUID payer)
      throws Exception {
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + ledger.groupId() + "/expenses",
                    Map.of(
                        "description",
                        currency + " expense",
                        "amountMinor",
                        amount,
                        "currency",
                        currency,
                        "paidByUserId",
                        payer))
                .header("X-Ledger-Version", "2")
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isCreated());
  }

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  void legacyUsdCreateReceiptReplaysOriginalSnapshotAfterDeletionWithoutRewritingIt()
      throws Exception {
    var ledger = ledger("currency-legacy-receipt");
    var operation = java.util.UUID.randomUUID();
    var originalRequest =
        Map.of(
            "description",
            "Original receipt",
            "amountCents",
            2000,
            "paidByUserId",
            ledger.alice().user().id(),
            "operationId",
            operation);
    var initialResult =
        mockMvc
            .perform(
                jsonPost("/api/groups/" + ledger.groupId() + "/expenses", originalRequest)
                    .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    var initial = objectMapper.readTree(initialResult.getResponse().getContentAsString());
    String originalId = initial.path("id").asText();
    var oldReceipt = (tools.jackson.databind.node.ObjectNode) initial.deepCopy();
    oldReceipt.remove("currency");
    oldReceipt.remove("amountMinor");
    for (var participant : oldReceipt.path("participants")) {
      ((tools.jackson.databind.node.ObjectNode) participant).remove("currency");
      ((tools.jackson.databind.node.ObjectNode) participant).remove("shareMinor");
    }
    // SQL sets up an actual V12/V13-format receipt; assertions remain at the authenticated
    // interface.
    var oldIntent =
        (tools.jackson.databind.node.ObjectNode)
            objectMapper.readTree(
                jdbc.sql(
                        "SELECT intent FROM create_operations WHERE actor_id=? AND kind='expense' AND scope_id=? AND operation_id=?")
                    .params(
                        ledger.alice().user().id(),
                        java.util.UUID.fromString(ledger.groupId()),
                        operation)
                    .query(String.class)
                    .single());
    oldIntent.remove("currency");
    oldIntent.remove("amountMinor");
    String originalSnapshot = objectMapper.writeValueAsString(oldReceipt);
    jdbc.sql(
            "UPDATE create_operations SET intent=?, response=? WHERE actor_id=? AND kind='expense' AND scope_id=? AND operation_id=?")
        .params(
            objectMapper.writeValueAsString(oldIntent),
            originalSnapshot,
            ledger.alice().user().id(),
            java.util.UUID.fromString(ledger.groupId()),
            operation)
        .update();
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/groups/"
                        + ledger.groupId()
                        + "/expenses/"
                        + originalId
                        + "?expectedVersion=0")
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isNoContent());
    var replayRequest = new java.util.HashMap<String, Object>();
    replayRequest.put("description", "Original receipt");
    replayRequest.put("amountMinor", 2000);
    replayRequest.put("currency", "USD");
    replayRequest.put("paidByUserId", ledger.alice().user().id());
    replayRequest.put("operationId", operation);
    var replayResult =
        mockMvc
            .perform(
                jsonPost("/api/groups/" + ledger.groupId() + "/expenses", replayRequest)
                    .header("X-Ledger-Version", "2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    var replay = objectMapper.readTree(replayResult.getResponse().getContentAsString());
    assertThat(replay.path("id").asText()).isEqualTo(originalId);
    assertThat(replay.path("version").asLong()).isZero();
    assertThat(replay.path("amountMinor").asLong()).isEqualTo(2000);
    assertThat(replay.path("currency").asText()).isEqualTo("USD");
    assertThat(replay.path("participants").get(0).path("shareMinor").asLong()).isEqualTo(1000);
    replayRequest.put("currency", "JPY");
    mockMvc
        .perform(
            jsonPost("/api/groups/" + ledger.groupId() + "/expenses", replayRequest)
                .header("X-Ledger-Version", "2")
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isConflict());
    assertThat(read(ledger, "/expenses").size()).isZero();
    assertThat(
            jdbc.sql(
                    "SELECT response FROM create_operations WHERE actor_id=? AND kind='expense' AND scope_id=? AND operation_id=?")
                .params(
                    ledger.alice().user().id(),
                    java.util.UUID.fromString(ledger.groupId()),
                    operation)
                .query(String.class)
                .single())
        .isEqualTo(originalSnapshot);
  }

  @Test
  void legacyClientsReceiveUpgradeGuidanceInsteadOfMisreadingNonUsdMoney() throws Exception {
    var ledger = ledger("currency-legacy-client");
    expense(ledger, "JPY", 2000, ledger.alice().user().id());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/groups/" + ledger.groupId() + "/expenses")
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + ledger.groupId() + "/payments",
                    Map.of(
                        "payerUserId",
                        ledger.bob().user().id(),
                        "payeeUserId",
                        ledger.alice().user().id(),
                        "amountMinor",
                        1000,
                        "currency",
                        "JPY"))
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isConflict());
    assertThat(read(ledger, "/payments").size()).isZero();
  }

  @Test
  void dashboardPeopleGroupSpendingAndHeadlineTotalsArePartitionedByCurrency() throws Exception {
    var ledger = ledger("currency-dashboard");
    expense(ledger, "USD", 2000, ledger.alice().user().id());
    expense(ledger, "JPY", 2000, ledger.bob().user().id());
    var result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/dashboard")
                    .header("X-Ledger-Version", "2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    var dashboard = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(dashboard.path("totalNetCents").isNull()).isTrue();
    assertThat(dashboard.path("groups").get(0).path("totalSpentCents").isNull()).isTrue();
    assertThat(dashboard.path("totals").size()).isEqualTo(2);
    var totals = new java.util.HashMap<String, Long>();
    for (var total : dashboard.path("totals"))
      totals.put(total.path("currency").asText(), total.path("totalNetMinor").asLong());
    assertThat(totals).containsExactlyInAnyOrderEntriesOf(Map.of("USD", 1000L, "JPY", -1000L));
    assertThat(dashboard.path("people").size()).isEqualTo(2);
    var spent = new java.util.HashMap<String, Long>();
    for (var total : dashboard.path("groups").get(0).path("totals"))
      spent.put(total.path("currency").asText(), total.path("totalSpentMinor").asLong());
    assertThat(spent).containsExactlyInAnyOrderEntriesOf(Map.of("USD", 2000L, "JPY", 2000L));
  }

  @Test
  void settlingUsdLeavesJpyOpenAndUnlikeOppositeNetsCannotPermitDeparture() throws Exception {
    var ledger = ledger("currency-settle");
    expense(ledger, "USD", 2000, ledger.alice().user().id());
    expense(ledger, "JPY", 2000, ledger.bob().user().id());
    var obligations = read(ledger, "/balances/current-obligations");
    assertThat(obligations.size()).isEqualTo(2);
    assertThat(
            java.util.List.of(
                obligations.get(0).path("currency").asText(),
                obligations.get(1).path("currency").asText()))
        .containsExactlyInAnyOrder("USD", "JPY");
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/groups/" + ledger.groupId() + "/members/" + ledger.bob().user().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.bob().accessToken())))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + ledger.groupId() + "/payments",
                    Map.of(
                        "payerUserId",
                        ledger.bob().user().id(),
                        "payeeUserId",
                        ledger.alice().user().id(),
                        "amountMinor",
                        1000,
                        "currency",
                        "USD",
                        "operationId",
                        java.util.UUID.randomUUID()))
                .header("X-Ledger-Version", "2")
                .header(HttpHeaders.AUTHORIZATION, bearer(ledger.alice().accessToken())))
        .andExpect(status().isCreated());
    var remaining = read(ledger, "/balances/current-obligations");
    assertThat(remaining.size()).isEqualTo(1);
    assertThat(remaining.get(0).path("currency").asText()).isEqualTo("JPY");
    assertThat(remaining.get(0).path("amountMinor").asLong()).isEqualTo(1000);
    assertThat(remaining.get(0).path("amountCents").isNull()).isTrue();
  }

  @Test
  void usdAndJpyBalancesRemainSeparateInsteadOfAddingUnlikeUnits() throws Exception {
    var alice = register("currency-mixed-a@example.test", "password123", "Alice");
    var bob = register("currency-mixed-b@example.test", "password123", "Bob");
    var created =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Mixed"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    String groupId =
        objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/members", Map.of("email", bob.user().email()))
                .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
        .andExpect(status().isOk());
    for (String currency : java.util.List.of("USD", "JPY")) {
      mockMvc
          .perform(
              jsonPost(
                      "/api/groups/" + groupId + "/expenses",
                      Map.of(
                          "description",
                          currency + " expense",
                          "amountMinor",
                          2000,
                          "currency",
                          currency,
                          "paidByUserId",
                          alice.user().id()))
                  .header("X-Ledger-Version", "2")
                  .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
          .andExpect(status().isCreated());
    }
    var result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/groups/" + groupId + "/balances")
                    .header("X-Ledger-Version", "2")
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    var balances = objectMapper.readTree(result.getResponse().getContentAsString()).get("balances");
    var observed = new java.util.HashMap<String, Long>();
    for (var balance : balances)
      observed.put(
          balance.path("user").path("email").asText() + ":" + balance.path("currency").asText(),
          balance.path("netMinor").asLong());
    assertThat(observed)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                alice.user().email() + ":USD",
                1000L,
                bob.user().email() + ":USD",
                -1000L,
                alice.user().email() + ":JPY",
                1000L,
                bob.user().email() + ":JPY",
                -1000L));
  }

  @Test
  void legacyUsdExpenseHasExplicitDenominationWithoutChangingItsAmount() throws Exception {
    var alice = register("currency-usd@example.test", "password123", "Alice");
    var group =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Currency"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    String groupId =
        objectMapper.readTree(group.getResponse().getContentAsString()).get("id").asText();
    var result =
        mockMvc
            .perform(
                jsonPost(
                        "/api/groups/" + groupId + "/expenses",
                        Map.of(
                            "description",
                            "Old USD writer",
                            "amountCents",
                            1234,
                            "paidByUserId",
                            alice.user().id()))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    var expense = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(expense.path("currency").asText()).isEqualTo("USD");
    assertThat(expense.path("amountMinor").asLong()).isEqualTo(1234);
    assertThat(expense.path("amountCents").asLong()).isEqualTo(1234);
    assertThat(expense.path("participants").get(0).path("shareMinor").asLong()).isEqualTo(1234);
  }
}
