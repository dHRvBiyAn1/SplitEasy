package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.entity.SplitType;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.service.ExpenseService;
import com.spliteasy.service.GroupService;
import com.spliteasy.service.MembershipGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@AutoConfigureMockMvc
class ExpenseEditConflictIntegrationTest extends AbstractIntegrationTest {

  @MockitoSpyBean ExpenseRepository expenseRepository;
  @MockitoSpyBean MembershipGuard membershipGuard;
  @Autowired ExpenseService expenseService;
  @Autowired GroupService groupService;
  @Autowired JdbcTemplate jdbc;

  @Test
  void concurrentEditsSerializeAndKeepTheFinalExpenseAndLedger() throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    String id = UUID.randomUUID().toString();
    AuthResponse alice = register(id + "-a@example.com", "password123", "Alice");
    AuthResponse bob = register(id + "-b@example.com", "password123", "Bob");
    UUID groupId =
        groupService.createGroup(alice.user().id(), new CreateGroupRequest("Concurrent edit")).id();
    groupService.addMember(alice.user().id(), groupId, bob.user().email());
    UUID expenseId =
        expenseService
            .createExpense(
                alice.user().id(),
                groupId,
                new CreateExpenseRequest("Original", 600, alice.user().id(), null))
            .id();
    String path = "/api/groups/" + groupId + "/expenses/" + expenseId;

    CyclicBarrier bothAuthorized = new CyclicBarrier(2);
    AtomicInteger authorizations = new AtomicInteger();
    doAnswer(
            call -> {
              call.callRealMethod();
              if (authorizations.incrementAndGet() <= 2) bothAuthorized.await(20, TimeUnit.SECONDS);
              return null;
            })
        .when(membershipGuard)
        .requireMember(eq(groupId), any());

    List<MvcResult> results;
    try (var edits = Executors.newFixedThreadPool(2)) {
      var first =
          edits.submit(
              () ->
                  edit(
                      path,
                      alice,
                      new CreateExpenseRequest("Alice edit", 1800, alice.user().id(), null)));
      var second =
          edits.submit(
              () ->
                  edit(
                      path,
                      bob,
                      new CreateExpenseRequest(
                          "Bob edit",
                          2400,
                          bob.user().id(),
                          null,
                          SplitType.UNEQUAL,
                          List.of(
                              new SplitInput(alice.user().id(), 1000),
                              new SplitInput(bob.user().id(), 1400)))));
      results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    }

    // Financial writes serialize. Both edits may succeed; separate stale forms have no version
    // contract.
    assertThat(results).extracting(r -> r.getResponse().getStatus()).containsExactly(200, 200);
    MvcResult fresh =
        mockMvc
            .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    ExpenseResponse saved =
        objectMapper.readValue(fresh.getResponse().getContentAsString(), ExpenseResponse.class);
    assertThat(
            List.of(
                objectMapper.readValue(
                    results.getFirst().getResponse().getContentAsString(), ExpenseResponse.class),
                objectMapper.readValue(
                    results.getLast().getResponse().getContentAsString(), ExpenseResponse.class)))
        .contains(saved);
    boolean aliceWon = saved.description().equals("Alice edit");
    assertThat(saved.description()).isEqualTo(aliceWon ? "Alice edit" : "Bob edit");
    assertThat(saved.amountCents()).isEqualTo(aliceWon ? 1800 : 2400);
    assertThat(saved.paidBy().id()).isEqualTo(aliceWon ? alice.user().id() : bob.user().id());
    assertThat(saved.splitType()).isEqualTo(aliceWon ? SplitType.EQUAL : SplitType.UNEQUAL);
    assertThat(saved.participants())
        .extracting(s -> s.user().id())
        .containsExactly(alice.user().id(), bob.user().id());
    assertThat(saved.participants())
        .extracting(s -> s.shareCents())
        .containsExactly(aliceWon ? 900L : 1000L, aliceWon ? 900L : 1400L);
    assertThat(saved.participants()).hasSize(2);
    assertThat(saved.participants()).extracting(s -> s.user().id()).doesNotHaveDuplicates();
    assertThat(saved.participants().stream().mapToLong(s -> s.shareCents()).sum())
        .isEqualTo(saved.amountCents());
    mockMvc
        .perform(
            get("/api/groups/" + groupId + "/expenses")
                .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()")
                .value(1));

    MvcResult balanceResult =
        mockMvc
            .perform(
                get("/api/groups/" + groupId + "/balances")
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    GroupBalancesResponse ledger =
        objectMapper.readValue(
            balanceResult.getResponse().getContentAsString(), GroupBalancesResponse.class);
    assertThat(ledger.balances()).hasSize(2);
    assertThat(ledger.balances().stream().mapToLong(b -> b.netCents()).sum()).isZero();
    assertThat(
            ledger.balances().stream()
                .collect(Collectors.toMap(b -> b.user().id(), b -> b.netCents())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                alice.user().id(),
                aliceWon ? 900L : -1000L,
                bob.user().id(),
                aliceWon ? -900L : 1000L));
  }

  @Test
  void detectedPersistenceConflictReturnsReloadGuidanceAndRollsBackTheEdit() throws Exception {
    String id = UUID.randomUUID().toString();
    AuthResponse alice = register(id + "-a@example.com", "password123", "Alice");
    AuthResponse bob = register(id + "-b@example.com", "password123", "Bob");
    UUID groupId =
        groupService
            .createGroup(alice.user().id(), new CreateGroupRequest("Persistence conflict"))
            .id();
    groupService.addMember(alice.user().id(), groupId, bob.user().email());
    UUID expenseId =
        expenseService
            .createExpense(
                alice.user().id(),
                groupId,
                new CreateExpenseRequest("Original", 600, alice.user().id(), null))
            .id();
    AtomicBoolean removed = new AtomicBoolean();
    doAnswer(
            call -> {
              Object loaded =
                  mockingDetails(call.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(call);
              if (removed.compareAndSet(false, true)) {
                // An external database writer bypasses the group protocol: exercise real stale-row
                // translation.
                CompletableFuture.runAsync(
                        () -> jdbc.update("delete from expenses where id = ?", expenseId))
                    .get(20, TimeUnit.SECONDS);
              }
              return loaded;
            })
        .when(expenseRepository)
        .findByIdFetchAll(expenseId);
    String path = "/api/groups/" + groupId + "/expenses/" + expenseId;
    MvcResult conflict =
        edit(
            path,
            alice,
            new CreateExpenseRequest("Conflicting edit", 1800, alice.user().id(), null));
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
    assertThat(
            objectMapper
                .readTree(conflict.getResponse().getContentAsString())
                .get("message")
                .asText())
        .contains("Reload", "review");
    mockMvc
        .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
        .andExpect(status().isNotFound());
    var response =
        mockMvc
            .perform(
                get("/api/groups/" + groupId + "/balances")
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    GroupBalancesResponse balances =
        objectMapper.readValue(
            response.getResponse().getContentAsString(), GroupBalancesResponse.class);
    assertThat(balances.balances()).hasSize(2).allSatisfy(b -> assertThat(b.netCents()).isZero());
  }

  private MvcResult edit(String path, AuthResponse user, CreateExpenseRequest request)
      throws Exception {
    return mockMvc
        .perform(
            put(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(request)))
        .andReturn();
  }
}
