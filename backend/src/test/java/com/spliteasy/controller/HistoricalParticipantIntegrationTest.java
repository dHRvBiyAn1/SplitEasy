package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.balance.PersonBalance;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class HistoricalParticipantIntegrationTest extends AbstractIntegrationTest {
  @Autowired JdbcTemplate jdbc;

  @Test
  void historicalCounterpartyRemainsVisibleWithoutMembership() throws Exception {
    Fixture f = fixture();
    expense(f, 2000, f.a(), List.of(f.a().user().id(), f.b().user().id()));
    oldRemove(f);
    assertBalances(f, Map.of(f.a().user().id(), 1000L, f.b().user().id(), -1000L));
    assertThat(Arrays.asList(read(f, "/balances/mine", PersonBalance[].class)))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.user().id()).isEqualTo(f.b().user().id());
              assertThat(p.netCents()).isEqualTo(1000);
            });
  }

  @Test
  void damagedSettledDepartureStillReturnsZeroSumLedger() throws Exception {
    Fixture f = fixture();
    ExpenseResponse original =
        expense(f, 2000, f.a(), List.of(f.a().user().id(), f.b().user().id()));
    payment(f);
    oldRemove(f);
    // Old releases permitted deletion after the settled departure; reproduce that stored damage.
    jdbc.update("delete from expenses where id = ?", original.id());
    assertBalances(f, Map.of(f.a().user().id(), -1000L, f.b().user().id(), 1000L));
    assertThat(Arrays.asList(read(f, "/balances/mine", PersonBalance[].class)))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.user().id()).isEqualTo(f.b().user().id());
              assertThat(p.netCents()).isEqualTo(-1000);
            });
  }

  @Test
  void paymentOnlyCounterpartyIsIncluded() throws Exception {
    Fixture f = fixture();
    payment(f);
    oldRemove(f);
    assertBalances(f, Map.of(f.a().user().id(), -1000L, f.b().user().id(), 1000L));
    assertThat(Arrays.asList(read(f, "/balances/mine", PersonBalance[].class)))
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.user().id()).isEqualTo(f.b().user().id());
              assertThat(p.netCents()).isEqualTo(-1000);
            });
  }

  @Test
  void historicalParticipationDoesNotAuthorizeGroupAccess() throws Exception {
    Fixture f = fixture();
    expense(f, 2000, f.a(), List.of(f.a().user().id(), f.b().user().id()));
    oldRemove(f);
    for (UUID group : List.of(f.groupId(), UUID.randomUUID())) {
      for (String suffix : List.of("", "/balances", "/balances/mine", "/payments", "/expenses")) {
        mockMvc
            .perform(auth(get("/api/groups/" + group + suffix), f.b()))
            .andExpect(status().isForbidden());
      }
    }
    assertThat(read(f, "", GroupResponse.class).members())
        .extracting(u -> u.id())
        .containsExactly(f.a().user().id());
    var departedDashboard =
        body(
            auth(get("/api/dashboard"), f.b()),
            200,
            com.spliteasy.dto.dashboard.DashboardResponse.class);
    assertThat(departedDashboard.groupCount()).isZero();
    assertThat(departedDashboard.groups()).isEmpty();
    assertThat(body(auth(get("/api/groups"), f.b()), 200, Object[].class)).isEmpty();
    var ownerDashboard =
        body(
            auth(get("/api/dashboard"), f.a()),
            200,
            com.spliteasy.dto.dashboard.DashboardResponse.class);
    assertThat(ownerDashboard.groups())
        .singleElement()
        .satisfies(g -> assertThat(g.memberCount()).isEqualTo(1));
    assertThat(ownerDashboard.people())
        .singleElement()
        .satisfies(p -> assertThat(p.user().id()).isEqualTo(f.b().user().id()));
  }

  @Test
  void netZeroIntermediaryCannotLeaveWithOutstandingBilateralObligations() throws Exception {
    Fixture f = fixture();
    AuthResponse c = register(UUID.randomUUID() + "-c@example.com", "password123", "Carol");
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(c.user().email())), f.a()),
        200,
        GroupResponse.class);
    ExpenseResponse first = expense(f, 1000, f.b(), List.of(f.a().user().id()));
    ExpenseResponse second = expense(f, 1000, c, List.of(f.b().user().id()));
    var expected = Map.of(f.a().user().id(), -1000L, f.b().user().id(), 0L, c.user().id(), 1000L);
    assertBalances(f, expected);
    assertThat(body(auth(get(path(f, "/balances/mine")), f.b()), 200, PersonBalance[].class))
        .extracting(p -> p.netCents())
        .containsExactlyInAnyOrder(1000L, -1000L);
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.a()))
        .andExpect(status().isConflict());
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
        .andExpect(status().isConflict());
    assertThat(read(f, "", GroupResponse.class).members()).hasSize(3);
    assertThat(read(f, "/expenses/" + first.id(), ExpenseResponse.class)).isEqualTo(first);
    assertThat(read(f, "/expenses/" + second.id(), ExpenseResponse.class)).isEqualTo(second);
    assertThat(read(f, "/payments", Object[].class)).isEmpty();
    assertBalances(f, expected);
  }

  @Test
  void cannotDeleteSettledHistoryUntilFormerParticipantsAreReadded() throws Exception {
    Fixture f = fixture();
    ExpenseResponse original =
        expense(f, 2000, f.a(), List.of(f.a().user().id(), f.b().user().id()));
    payment(f);
    Object[] payments = read(f, "/payments", Object[].class);
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
        .andExpect(status().isNoContent());
    assertBalances(f, Map.of(f.a().user().id(), 0L, f.b().user().id(), 0L));
    mockMvc
        .perform(auth(delete(path(f, "/expenses/" + original.id())), f.a()))
        .andExpect(status().isConflict());
    assertThat(read(f, "/expenses/" + original.id(), ExpenseResponse.class)).isEqualTo(original);
    assertThat(read(f, "/payments", Object[].class)).containsExactly(payments);
    assertBalances(f, Map.of(f.a().user().id(), 0L, f.b().user().id(), 0L));
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(f.b().user().email())), f.a()),
        200,
        GroupResponse.class);
    mockMvc
        .perform(auth(delete(path(f, "/expenses/" + original.id())), f.a()))
        .andExpect(status().isNoContent());
    assertThat(read(f, "/expenses", Object[].class)).isEmpty();
    assertThat(read(f, "/payments", Object[].class)).containsExactly(payments);
    assertBalances(f, Map.of(f.a().user().id(), -1000L, f.b().user().id(), 1000L));
  }

  @Test
  void cannotReplaceFormerParticipantsInSettledHistoryUntilReadded() throws Exception {
    Fixture f = fixture();
    ExpenseResponse original =
        expense(f, 2000, f.a(), List.of(f.a().user().id(), f.b().user().id()));
    payment(f);
    Object[] payments = read(f, "/payments", Object[].class);
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
        .andExpect(status().isNoContent());
    var changed =
        new CreateExpenseRequest(
            "Replace former person", 3000, f.a().user().id(), List.of(f.a().user().id()));
    mockMvc
        .perform(
            auth(
                put(path(f, "/expenses/" + original.id()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(changed)),
                f.a()))
        .andExpect(status().isConflict());
    assertThat(read(f, "/expenses/" + original.id(), ExpenseResponse.class)).isEqualTo(original);
    assertThat(read(f, "/payments", Object[].class)).containsExactly(payments);
    assertBalances(f, Map.of(f.a().user().id(), 0L, f.b().user().id(), 0L));
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(f.b().user().email())), f.a()),
        200,
        GroupResponse.class);
    body(
        auth(
            put(path(f, "/expenses/" + original.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(changed)),
            f.b()),
        200,
        ExpenseResponse.class);
    assertThat(read(f, "/expenses/" + original.id(), ExpenseResponse.class).participants())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.user().id()).isEqualTo(f.a().user().id());
              assertThat(p.shareCents()).isEqualTo(3000);
            });
    assertBalances(f, Map.of(f.a().user().id(), -1000L, f.b().user().id(), 1000L));
  }

  @Test
  void departedUninvolvedPersonDoesNotFreezeNormalHistory() throws Exception {
    Fixture f = fixture();
    ExpenseResponse original = expense(f, 2000, f.a(), List.of(f.a().user().id()));
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
        .andExpect(status().isNoContent());
    var changed =
        new CreateExpenseRequest(
            "Corrected solo expense", 3000, f.a().user().id(), List.of(f.a().user().id()));
    body(
        auth(
            put(path(f, "/expenses/" + original.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(changed)),
            f.a()),
        200,
        ExpenseResponse.class);
    ExpenseResponse saved = read(f, "/expenses/" + original.id(), ExpenseResponse.class);
    assertThat(saved.description()).isEqualTo("Corrected solo expense");
    assertThat(saved.amountCents()).isEqualTo(3000);
    assertThat(saved.participants())
        .singleElement()
        .satisfies(p -> assertThat(p.shareCents()).isEqualTo(3000));
    mockMvc
        .perform(auth(delete(path(f, "/expenses/" + original.id())), f.a()))
        .andExpect(status().isNoContent());
    assertThat(read(f, "/expenses", Object[].class)).isEmpty();
    assertBalances(f, Map.of(f.a().user().id(), 0L));
  }

  @Test
  void departedPayerOutsideTheSharesStillProtectsTheOldExpense() throws Exception {
    Fixture f = fixture();
    ExpenseResponse original = expense(f, 2000, f.b(), List.of(f.a().user().id()));
    body(
        auth(
            jsonPost(
                path(f, "/payments"),
                new RecordPaymentRequest(f.a().user().id(), f.b().user().id(), 2000)),
            f.a()),
        201,
        Object.class);
    mockMvc
        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(auth(delete(path(f, "/expenses/" + original.id())), f.a()))
        .andExpect(status().isConflict());
    var changed =
        new CreateExpenseRequest(
            "Replacement payer", 2000, f.a().user().id(), List.of(f.a().user().id()));
    mockMvc
        .perform(
            auth(
                put(path(f, "/expenses/" + original.id()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(changed)),
                f.a()))
        .andExpect(status().isConflict());
    assertThat(read(f, "/expenses/" + original.id(), ExpenseResponse.class)).isEqualTo(original);
    assertBalances(f, Map.of(f.a().user().id(), 0L, f.b().user().id(), 0L));
  }

  private Fixture fixture() throws Exception {
    String id = UUID.randomUUID().toString();
    AuthResponse a = register(id + "-a@example.com", "password123", "Alice");
    AuthResponse b = register(id + "-b@example.com", "password123", "Bob");
    GroupResponse g =
        body(
            auth(jsonPost("/api/groups", new CreateGroupRequest("History")), a),
            201,
            GroupResponse.class);
    body(
        auth(
            jsonPost("/api/groups/" + g.id() + "/members", new AddMemberRequest(b.user().email())),
            a),
        200,
        GroupResponse.class);
    return new Fixture(a, b, g.id());
  }

  private ExpenseResponse expense(Fixture f, long amount, AuthResponse payer, List<UUID> people)
      throws Exception {
    ExpenseResponse created =
        body(
            auth(
                jsonPost(
                    path(f, "/expenses"),
                    new CreateExpenseRequest("Original", amount, payer.user().id(), people)),
                f.a()),
            201,
            ExpenseResponse.class);
    return read(f, "/expenses/" + created.id(), ExpenseResponse.class);
  }

  private void payment(Fixture f) throws Exception {
    body(
        auth(
            jsonPost(
                path(f, "/payments"),
                new RecordPaymentRequest(f.b().user().id(), f.a().user().id(), 1000)),
            f.a()),
        201,
        Object.class);
  }

  private void oldRemove(Fixture f) {
    jdbc.update(
        "delete from group_memberships where group_id = ? and user_id = ?",
        f.groupId(),
        f.b().user().id());
  }

  private void assertBalances(Fixture f, Map<UUID, Long> expected) throws Exception {
    var balances = read(f, "/balances", GroupBalancesResponse.class).balances();
    assertThat(balances.stream().mapToLong(b -> b.netCents()).sum()).isZero();
    assertThat(balances.stream().collect(Collectors.toMap(b -> b.user().id(), b -> b.netCents())))
        .containsExactlyInAnyOrderEntriesOf(expected);
  }

  private <T> T read(Fixture f, String suffix, Class<T> type) throws Exception {
    return body(auth(get(path(f, suffix)), f.a()), 200, type);
  }

  private <T> T body(MockHttpServletRequestBuilder request, int status, Class<T> type)
      throws Exception {
    var result = mockMvc.perform(request).andExpect(status().is(status)).andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), type);
  }

  private MockHttpServletRequestBuilder auth(
      MockHttpServletRequestBuilder request, AuthResponse user) {
    return request.header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()));
  }

  private String path(Fixture f, String suffix) {
    return "/api/groups/" + f.groupId() + suffix;
  }

  private record Fixture(AuthResponse a, AuthResponse b, UUID groupId) {}
}
