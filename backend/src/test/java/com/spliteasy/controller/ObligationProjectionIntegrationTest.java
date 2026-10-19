package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class ObligationProjectionIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void simplifiedChainAndPaymentAgreeAcrossEveryFinancialSurface() throws Exception {
    Fixture f = chain();
    JsonNode mine = read(f.a(), "/api/groups/" + f.group() + "/balances/mine");
    assertThat(mine.size()).isEqualTo(1);
    assertThat(mine.get(0).path("user").path("id").asText())
        .isEqualTo(f.c().user().id().toString());
    assertThat(mine.get(0).path("netCents").asLong()).isEqualTo(-1000);
    assertThat(read(f.b(), "/api/groups/" + f.group() + "/balances/mine").isEmpty()).isTrue();
    pay(f, f.a(), f.c(), 1000);
    for (AuthResponse user : List.of(f.a(), f.b(), f.c())) {
      assertThat(read(user, "/api/groups/" + f.group() + "/balances/mine").isEmpty()).isTrue();
      JsonNode dashboard = read(user, "/api/dashboard");
      assertThat(dashboard.path("owedCents").asLong()).isZero();
      assertThat(dashboard.path("oweCents").asLong()).isZero();
      assertThat(dashboard.path("people").isEmpty()).isTrue();
      assertThat(dashboard.path("settlements").isEmpty()).isTrue();
    }
    assertThat(
            read(f.a(), "/api/groups/" + f.group() + "/debt-simplification")
                .path("transactions")
                .isEmpty())
        .isTrue();
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/obligations").size())
        .isEqualTo(3);
  }

  @Test
  void simplifiedIntermediaryCanLeaveButCannotBeExposedBySwitchingToDirect() throws Exception {
    Fixture f = chain();
    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/members/" + f.b().user().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(f.b().accessToken())))
        .andExpect(status().isNoContent());
    JsonNode before = read(f.a(), "/api/groups/" + f.group() + "/expenses");
    var result =
        mockMvc
            .perform(
                patch("/api/groups/" + f.group())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Rejected rename\",\"simplifyDebts\":false}")
                    .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
            .andExpect(status().isConflict())
            .andReturn();
    assertThat(result.getResponse().getContentAsString()).contains("Re-add", "former");
    assertThat(read(f.a(), "/api/groups/" + f.group()).path("simplifyDebts").asBoolean()).isTrue();
    assertThat(read(f.a(), "/api/groups/" + f.group()).path("name").asText()).isEqualTo("Chain");
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/expenses")).isEqualTo(before);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/payments").isEmpty()).isTrue();
    mockMvc
        .perform(
            get("/api/groups/" + f.group() + "/balances/current-obligations")
                .header(HttpHeaders.AUTHORIZATION, bearer(f.b().accessToken())))
        .andExpect(status().isForbidden());
  }

  @Test
  void directCycleRemainsActionableWhenNetPreviewIsEmptyAndWarnsAboutOffsettingDebts()
      throws Exception {
    Fixture f = chain();
    mode(f, false, 200);
    assertThat(read(f.b(), "/api/groups/" + f.group() + "/balances/mine").size()).isEqualTo(2);
    pay(f, f.a(), f.c(), 1000);
    for (AuthResponse member : List.of(f.a(), f.b(), f.c())) {
      JsonNode dashboard = read(member, "/api/dashboard");
      assertThat(dashboard.path("owedCents").asLong()).isEqualTo(1000);
      assertThat(dashboard.path("oweCents").asLong()).isEqualTo(1000);
      assertThat(dashboard.path("settlements").size()).isEqualTo(2);
      assertThat(dashboard.path("people").size()).isEqualTo(2);
    }
    JsonNode preview = read(f.a(), "/api/groups/" + f.group() + "/debt-simplification");
    assertThat(preview.path("transactions").isEmpty()).isTrue();
    assertThat(preview.path("warning").asText())
        .contains("may leave offsetting direct obligations");
    JsonNode raw = read(f.a(), "/api/groups/" + f.group() + "/balances/obligations");
    JsonNode expenses = read(f.a(), "/api/groups/" + f.group() + "/expenses");
    JsonNode payments = read(f.a(), "/api/groups/" + f.group() + "/payments");
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/current-obligations"))
        .isEqualTo(raw);
    mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/members/" + f.b().user().id())
                .header(HttpHeaders.AUTHORIZATION, bearer(f.b().accessToken())))
        .andExpect(status().isConflict());
    mode(f, true, 200);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/current-obligations").isEmpty())
        .isTrue();
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/obligations")).isEqualTo(raw);
    mode(f, false, 200);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/obligations")).isEqualTo(raw);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/expenses")).isEqualTo(expenses);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/payments")).isEqualTo(payments);
  }

  @Test
  void equalAmountsUseStableUuidTiesAcrossPreviewCurrentMineAndDashboard() throws Exception {
    Fixture f = chain();
    AuthResponse d = register("d-" + UUID.randomUUID() + "@test.com", "password123", "D");
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/members", new AddMemberRequest(d.user().email()))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/expenses",
                    new CreateExpenseRequest(
                        "Second creditor", 1000, d.user().id(), List.of(f.b().user().id())))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
        .andExpect(status().isCreated());
    var debtors =
        List.of(f.a(), f.b()).stream()
            .sorted(java.util.Comparator.comparing(u -> u.user().id()))
            .toList();
    var creditors =
        List.of(f.c(), d).stream()
            .sorted(java.util.Comparator.comparing(u -> u.user().id()))
            .toList();
    // Make display-name order oppose UUID order; names must never choose equal-amount matches.
    jdbc.update(
        "update users set display_name = ? where id = ?", "Z debtor", debtors.get(0).user().id());
    jdbc.update(
        "update users set display_name = ? where id = ?", "A debtor", debtors.get(1).user().id());
    jdbc.update(
        "update users set display_name = ? where id = ?",
        "A creditor",
        creditors.get(0).user().id());
    jdbc.update(
        "update users set display_name = ? where id = ?",
        "Z creditor",
        creditors.get(1).user().id());
    for (int attempt = 0; attempt < 2; attempt++) {
      JsonNode preview =
          read(f.a(), "/api/groups/" + f.group() + "/debt-simplification").path("transactions");
      JsonNode current = read(f.a(), "/api/groups/" + f.group() + "/balances/current-obligations");
      assertThat(current).isEqualTo(preview);
      assertThat(current.size()).isEqualTo(2);
      for (int i = 0; i < 2; i++) {
        assertThat(current.get(i).path("from").path("id").asText())
            .isEqualTo(debtors.get(i).user().id().toString());
        assertThat(current.get(i).path("to").path("id").asText())
            .isEqualTo(creditors.get(i).user().id().toString());
        assertThat(current.get(i).path("amountCents").asLong()).isEqualTo(1000);
        JsonNode mine = read(debtors.get(i), "/api/groups/" + f.group() + "/balances/mine");
        assertThat(mine.get(0).path("netCents").asLong()).isEqualTo(-1000);
        assertThat(mine.get(0).path("user").path("id").asText())
            .isEqualTo(creditors.get(i).user().id().toString());
        JsonNode theirs = read(creditors.get(i), "/api/groups/" + f.group() + "/balances/mine");
        assertThat(theirs.get(0).path("netCents").asLong()).isEqualTo(1000);
        assertThat(theirs.get(0).path("user").path("id").asText())
            .isEqualTo(debtors.get(i).user().id().toString());
        assertThat(
                read(debtors.get(i), "/api/dashboard")
                    .path("settlements")
                    .get(0)
                    .path("counterparty")
                    .path("id")
                    .asText())
            .isEqualTo(creditors.get(i).user().id().toString());
      }
    }
  }

  @Test
  void simplifiedTargetWithFormerFinancialPrincipalRequiresExplicitRepair() throws Exception {
    Fixture f = chain();
    mode(f, false, 200);
    jdbc.update(
        "delete from group_memberships where group_id = ? and user_id = ?",
        UUID.fromString(f.group()),
        f.c().user().id());
    JsonNode raw = read(f.a(), "/api/groups/" + f.group() + "/balances/obligations");
    mode(f, true, 409);
    assertThat(read(f.a(), "/api/groups/" + f.group()).path("simplifyDebts").asBoolean()).isFalse();
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/obligations")).isEqualTo(raw);
    // A no-op mode assignment remains a no-op, even for old damaged history.
    mode(f, false, 200);
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/members",
                    new AddMemberRequest(f.c().user().email()))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
        .andExpect(status().isOk());
    mode(f, true, 200);
    assertThat(read(f.a(), "/api/groups/" + f.group() + "/balances/current-obligations").size())
        .isEqualTo(1);
  }

  private void mode(Fixture f, boolean simplified, int status) throws Exception {
    mockMvc
        .perform(
            patch("/api/groups/" + f.group())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"simplifyDebts\":" + simplified + "}")
                .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
        .andExpect(status().is(status));
  }

  private record Fixture(AuthResponse a, AuthResponse b, AuthResponse c, String group) {}

  private Fixture chain() throws Exception {
    String suffix = UUID.randomUUID().toString();
    AuthResponse a = register("a-" + suffix + "@test.com", "password123", "A");
    AuthResponse b = register("b-" + suffix + "@test.com", "password123", "B");
    AuthResponse c = register("c-" + suffix + "@test.com", "password123", "C");
    String group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", new CreateGroupRequest("Chain"))
                            .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("id")
            .asText();
    for (AuthResponse member : List.of(b, c)) {
      mockMvc
          .perform(
              jsonPost(
                      "/api/groups/" + group + "/members",
                      new AddMemberRequest(member.user().email()))
                  .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
          .andExpect(status().isOk());
    }
    for (AuthResponse[] pair : List.of(new AuthResponse[] {a, b}, new AuthResponse[] {b, c})) {
      mockMvc
          .perform(
              jsonPost(
                      "/api/groups/" + group + "/expenses",
                      new CreateExpenseRequest(
                          "Chain link", 1000, pair[1].user().id(), List.of(pair[0].user().id())))
                  .header(HttpHeaders.AUTHORIZATION, bearer(a.accessToken())))
          .andExpect(status().isCreated());
    }
    return new Fixture(a, b, c, group);
  }

  private void pay(Fixture f, AuthResponse payer, AuthResponse payee, long amount)
      throws Exception {
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/payments",
                    new RecordPaymentRequest(payer.user().id(), payee.user().id(), amount))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.a().accessToken())))
        .andExpect(status().isCreated());
  }

  private JsonNode read(AuthResponse user, String path) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
