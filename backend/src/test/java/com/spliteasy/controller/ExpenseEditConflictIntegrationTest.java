package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.common.ErrorResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.entity.SplitType;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.service.ExpenseService;
import com.spliteasy.service.GroupService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@AutoConfigureMockMvc
class ExpenseEditConflictIntegrationTest extends AbstractIntegrationTest {

  @MockitoSpyBean ExpenseRepository expenseRepository;
  @Autowired ExpenseService expenseService;
  @Autowired GroupService groupService;

  @Test
  void concurrentEditsReturnOneConflictAndKeepTheWinningExpenseAndLedger() throws Exception {
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

    CyclicBarrier bothLoaded = new CyclicBarrier(2);
    AtomicInteger loads = new AtomicInteger();
    doAnswer(
            invocation -> {
              // Delegate to the real Spring Data query; only coordinate after both transactions
              // have loaded the same participant rows. No fake query results or sleeps.
              Object expense =
                  mockingDetails(invocation.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(invocation);
              if (loads.incrementAndGet() <= 2) {
                bothLoaded.await(20, TimeUnit.SECONDS);
              }
              return expense;
            })
        .when(expenseRepository)
        .findByIdFetchAll(expenseId);

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

    // Each user edit runs once; the loser must never be retried over the winner's commit.
    assertThat(loads).hasValue(2);
    assertThat(results)
        .extracting(r -> r.getResponse().getStatus())
        .containsExactlyInAnyOrder(200, 409);
    MvcResult winner =
        results.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
    MvcResult loser =
        results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
    ErrorResponse conflict =
        objectMapper.readValue(loser.getResponse().getContentAsString(), ErrorResponse.class);
    assertThat(conflict.status()).isEqualTo(409);
    assertThat(conflict.error()).isEqualTo("Conflict");
    assertThat(conflict.message()).contains("Reload", "review");
    ExpenseResponse saved =
        objectMapper.readValue(winner.getResponse().getContentAsString(), ExpenseResponse.class);
    boolean aliceWon = results.getFirst().getResponse().getStatus() == 200;
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
    MvcResult fresh =
        mockMvc
            .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(
            objectMapper.readValue(fresh.getResponse().getContentAsString(), ExpenseResponse.class))
        .isEqualTo(saved);
    assertThat(saved.participants()).hasSize(2);
    assertThat(saved.participants()).extracting(s -> s.user().id()).doesNotHaveDuplicates();
    assertThat(saved.participants().stream().mapToLong(s -> s.shareCents()).sum())
        .isEqualTo(saved.amountCents());
    assertThat(expenseRepository.findSummariesByGroupId(groupId)).hasSize(1);

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
    ledger
        .balances()
        .forEach(
            balance -> {
              long share =
                  saved.participants().stream()
                      .filter(s -> s.user().id().equals(balance.user().id()))
                      .findFirst()
                      .orElseThrow()
                      .shareCents();
              long paid = balance.user().id().equals(saved.paidBy().id()) ? saved.amountCents() : 0;
              assertThat(balance.netCents()).isEqualTo(paid - share);
            });
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
