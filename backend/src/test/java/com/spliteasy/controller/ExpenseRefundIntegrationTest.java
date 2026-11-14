package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * B01 refunds: money coming back out of one expense, reversing that expense's own allocation.
 * Amounts stay positive; the reader subtracts the stored allocation.
 */
@AutoConfigureMockMvc
class ExpenseRefundIntegrationTest extends AbstractIntegrationTest {

  record Fixture(AuthResponse alice, AuthResponse bob, AuthResponse carol, String group) {}

  Fixture fixture() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var a = register("ra-" + suffix + "@example.com", "password123", "Alice");
    var b = register("rb-" + suffix + "@example.com", "password123", "Bob");
    var c = register("rc-" + suffix + "@example.com", "password123", "Carol");
    MvcResult g =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Refunds"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    String groupId = objectMapper.readTree(g.getResponse().getContentAsString()).get("id").asText();
    for (var member : List.of(b, c)) {
      mockMvc
          .perform(
              jsonPost(
                      "/api/groups/" + groupId + "/members",
                      new AddMemberRequest(member.user().email()))
                  .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
          .andExpect(status().isOk());
    }
    return new Fixture(a, b, c, groupId);
  }

  /** An equal 100.00 dinner paid by Alice: shares 3334 / 3333 / 3333, payer absorbing the cent. */
  String dinner(Fixture f, long amountMinor, String currency) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("description", "Dinner");
    body.put("amountMinor", amountMinor);
    body.put("currency", currency);
    body.put("paidByUserId", f.alice().user().id());
    body.put("splitType", "EQUAL");
    MvcResult r =
        mockMvc
            .perform(
                jsonPost("/api/groups/" + f.group() + "/expenses", body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken()))
                    .header("X-Ledger-Version", "2"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
  }

  Map<String, Object> refund(long amountMinor, String currency, UUID operationId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amountMinor", amountMinor);
    body.put("currency", currency);
    body.put("refundedOn", "2026-03-06");
    body.put("note", "Restaurant returned part of the bill");
    if (operationId != null) body.put("operationId", operationId);
    return body;
  }

  MvcResult postRefund(Fixture f, String token, String expenseId, Map<String, Object> body)
      throws Exception {
    return mockMvc
        .perform(
            jsonPost("/api/groups/" + f.group() + "/expenses/" + expenseId + "/refunds", body)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andReturn();
  }

  JsonNode read(Fixture f, String token, String path) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                get("/api/groups/" + f.group() + path)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token))
                    .header("X-Ledger-Version", "2"))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString());
  }

  Map<String, Long> nets(Fixture f, String currency) throws Exception {
    Map<String, Long> byParticipant = new LinkedHashMap<>();
    for (JsonNode b : read(f, f.alice().accessToken(), "/balances").get("balances")) {
      if (b.get("currency").asText().equals(currency)) {
        byParticipant.put(b.get("user").get("id").asText(), b.get("netMinor").asLong());
      }
    }
    return byParticipant;
  }

  @Test
  void partialRefundReversesTheExpenseAllocationOnceAndKeepsBalancesZeroSum() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    String alice = f.alice().user().id().toString();
    String bob = f.bob().user().id().toString();
    String carol = f.carol().user().id().toString();
    assertThat(nets(f, "USD")).containsEntry(alice, 6666L).containsEntry(bob, -3333L);

    var response = postRefund(f, f.bob().accessToken(), expense, refund(3000, "USD", null));

    assertThat(response.getResponse().getStatus()).isEqualTo(201);
    var created = objectMapper.readTree(response.getResponse().getContentAsString());
    assertThat(created.get("amountMinor").asLong()).isEqualTo(3000);
    assertThat(created.get("expenseId").asText()).isEqualTo(expense);
    assertThat(created.get("receivedBy").get("id").asText()).isEqualTo(alice);
    assertThat(created.get("recordedBy").get("id").asText())
        .isEqualTo(f.bob().user().id().toString());
    // Pro rata by stored share, the receiving payer absorbing the two leftover cents.
    Map<String, Long> allocated = new LinkedHashMap<>();
    for (JsonNode share : created.get("participants")) {
      allocated.put(share.get("user").get("id").asText(), share.get("shareMinor").asLong());
    }
    assertThat(allocated).containsEntry(alice, 1002L).containsEntry(bob, 999L);
    assertThat(allocated.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(3000);

    // The refund receipt is storage-faithful too: a later read returns the identical document.
    var listed = read(f, f.alice().accessToken(), "/expenses/" + expense + "/refunds");
    assertThat(listed.size()).isEqualTo(1);
    assertThat(listed.get(0)).isEqualTo(created);

    var after = nets(f, "USD");
    assertThat(after).containsEntry(alice, 4668L).containsEntry(bob, -2334L);
    assertThat(after).containsEntry(carol, -2334L);
    assertThat(after.values().stream().mapToLong(Long::longValue).sum()).isZero();
  }

  @Test
  void identicalKeyedRetryReturnsTheOriginalRefundWithoutReversingTwice() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    UUID operation = UUID.randomUUID();

    var first = postRefund(f, f.bob().accessToken(), expense, refund(3000, "USD", operation));
    var before = nets(f, "USD");
    var retry = postRefund(f, f.bob().accessToken(), expense, refund(3000, "USD", operation));

    assertThat(first.getResponse().getStatus()).isEqualTo(201);
    assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    assertThat(objectMapper.readTree(retry.getResponse().getContentAsString()))
        .isEqualTo(objectMapper.readTree(first.getResponse().getContentAsString()));
    assertThat(nets(f, "USD")).isEqualTo(before);
    assertThat(read(f, f.alice().accessToken(), "/expenses/" + expense + "/refunds").size())
        .isEqualTo(1);
  }

  @Test
  void aChangedRefundUnderTheSameOperationIsConflict() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    UUID operation = UUID.randomUUID();
    postRefund(f, f.bob().accessToken(), expense, refund(3000, "USD", operation));

    var changed = postRefund(f, f.bob().accessToken(), expense, refund(4000, "USD", operation));

    assertThat(changed.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void refundBeyondTheUnrefundedRemainderIsRejectedWithTheLedgerUnchanged() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    postRefund(f, f.alice().accessToken(), expense, refund(7000, "USD", null));
    var before = nets(f, "USD");

    var tooMuch = postRefund(f, f.alice().accessToken(), expense, refund(3001, "USD", null));

    assertThat(tooMuch.getResponse().getStatus()).isEqualTo(400);
    assertThat(nets(f, "USD")).isEqualTo(before);
    // The exact remainder is still accepted.
    assertThat(
            postRefund(f, f.alice().accessToken(), expense, refund(3000, "USD", null))
                .getResponse()
                .getStatus())
        .isEqualTo(201);
  }

  @Test
  void refundInAnotherCurrencyIsRejected() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");

    var wrong = postRefund(f, f.alice().accessToken(), expense, refund(1000, "EUR", null));

    assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void refundOnADeletedExpenseIsRejected() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/expenses/" + expense)
                .param("expectedVersion", "0")
                .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken())))
        .andExpect(status().isNoContent());

    var response = postRefund(f, f.alice().accessToken(), expense, refund(1000, "USD", null));

    assertThat(response.getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  void deletingAnExpenseAlsoRemovesItsLiveRefundsFromEveryAggregate() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    postRefund(f, f.bob().accessToken(), expense, refund(3000, "USD", null));
    long version = read(f, f.alice().accessToken(), "/expenses/" + expense).get("version").asLong();

    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/expenses/" + expense)
                .param("expectedVersion", Long.toString(version))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken())))
        .andExpect(status().isNoContent());

    assertThat(nets(f, "USD").values()).allMatch(net -> net == 0L);
    assertThat(read(f, f.alice().accessToken(), "/balances/obligations").size()).isZero();
  }

  @Test
  void deletingARefundRestoresThePreRefundBalancesOnce() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    var before = nets(f, "USD");
    var created =
        objectMapper.readTree(
            postRefund(f, f.alice().accessToken(), expense, refund(3000, "USD", null))
                .getResponse()
                .getContentAsString());
    String refundId = created.get("id").asText();

    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/expenses/" + expense + "/refunds/" + refundId)
                .param("expectedVersion", "0")
                .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken())))
        .andExpect(status().isNoContent());

    assertThat(nets(f, "USD")).isEqualTo(before);
    assertThat(read(f, f.alice().accessToken(), "/expenses/" + expense + "/refunds").size())
        .isZero();
  }

  @Test
  void refundsInDifferentCurrenciesNeverMix() throws Exception {
    var f = fixture();
    String usd = dinner(f, 10000, "USD");
    dinner(f, 9000, "JPY");
    postRefund(f, f.alice().accessToken(), usd, refund(3000, "USD", null));

    String alice = f.alice().user().id().toString();
    assertThat(nets(f, "USD")).containsEntry(alice, 4668L);
    // JPY has exponent 0: 9000 split three ways is exact, so the JPY ledger is untouched.
    assertThat(nets(f, "JPY")).containsEntry(alice, 6000L);
  }

  @Test
  void nonMemberGetsForbiddenBeforeNotFoundOnRefundRoutes() throws Exception {
    var f = fixture();
    String expense = dinner(f, 10000, "USD");
    var outsider =
        register("rout-" + UUID.randomUUID() + "@example.com", "password123", "Outsider")
            .accessToken();
    String unknown = UUID.randomUUID().toString();

    mockMvc
        .perform(
            get("/api/groups/" + f.group() + "/expenses/" + unknown + "/refunds")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
    assertThat(postRefund(f, outsider, unknown, refund(100, "USD", null)).getResponse().getStatus())
        .isEqualTo(403);
    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/expenses/" + expense + "/refunds/" + unknown)
                .param("expectedVersion", "0")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
  }
}
