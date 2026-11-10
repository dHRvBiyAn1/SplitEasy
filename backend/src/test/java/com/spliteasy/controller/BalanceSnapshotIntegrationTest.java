package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.balance.PersonBalance;
import com.spliteasy.dto.balance.SimplifiedDebtsResponse;
import com.spliteasy.dto.balance.SuggestedTransaction;
import com.spliteasy.dto.dashboard.DashboardResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.repository.ExpenseParticipantRepository;
import com.spliteasy.repository.ExpenseRepository;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.PaymentRepository;
import com.spliteasy.service.ExpenseService;
import com.spliteasy.service.GroupService;
import com.spliteasy.service.PairwiseBalanceService;
import com.spliteasy.service.PaymentService;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL reads and commits; repository spies only coordinate their ordering. */
@AutoConfigureMockMvc
class BalanceSnapshotIntegrationTest extends AbstractIntegrationTest {

  @MockitoSpyBean ExpenseRepository expenseRepository;
  @MockitoSpyBean ExpenseParticipantRepository participantRepository;
  @MockitoSpyBean PaymentRepository paymentRepository;
  @Autowired GroupMembershipRepository membershipRepository;
  @Autowired GroupService groupService;
  @Autowired ExpenseService expenseService;
  @Autowired PaymentService paymentService;
  @Autowired PairwiseBalanceService pairwiseService;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;

  private final AtomicBoolean writeStarted = new AtomicBoolean();
  private final AtomicBoolean writeCommitted = new AtomicBoolean();

  @BeforeEach
  void requestsOwnTheirTransactions() {
    // A test-managed transaction would hide a missing production transaction boundary.
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  @ParameterizedTest(name = "balances keep one snapshot across a payment commit: {0}")
  @ValueSource(booleans = {false, true})
  void balancesRemainZeroSumAcrossAnInterveningCommit(boolean payment) throws Exception {
    Fixture f = fixture();
    expense(f, f.groupId(), f.owner().user().id(), 600);
    Map<UUID, Long> afterCommit = commitBetweenBalanceAggregates(f, payment);

    GroupBalancesResponse response = read(f, "/balances", GroupBalancesResponse.class);

    assertThat(writeCommitted).isTrue();
    assertBalances(response, f.balances(400, -200, -200));
    // A subsequent request must see the commit; the first response must not mix it in.
    assertBalances(read(f, "/balances", GroupBalancesResponse.class), afterCommit);
  }

  @ParameterizedTest(name = "pairwise snapshot: payment={0}, direct compute={1}")
  @CsvSource({"false, false", "false, true", "true, false", "true, true"})
  void pairwiseDoesNotCombineOppositeSidesOfDifferentSnapshots(
      boolean payment, boolean directCompute) throws Exception {
    Fixture f = fixture();
    expense(f, f.groupId(), f.owner().user().id(), 600);
    commitBetweenPairwiseAggregates(f, payment, () -> {});

    List<PersonBalance> response =
        directCompute
            ? pairwiseService.compute(f.owner().user().id(), f.groupId())
            : Arrays.asList(read(f, "/balances/mine", PersonBalance[].class));

    assertThat(writeCommitted).isTrue();
    assertThat(pairwise(response))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(f.bob().user().id(), 200L, f.carol().user().id(), 200L));
    assertThat(pairwise(pairwiseService.compute(f.owner().user().id(), f.groupId())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(f.bob().user().id(), payment ? 140L : 250L, f.carol().user().id(), 200L));
  }

  @ParameterizedTest(name = "simplification keeps one snapshot across a payment commit: {0}")
  @ValueSource(booleans = {false, true})
  void allSuggestedTransfersSettleTheObservedSnapshot(boolean payment) throws Exception {
    Fixture f = fixture();
    expense(f, f.groupId(), f.owner().user().id(), 600);
    Map<UUID, Long> afterCommit = commitBetweenBalanceAggregates(f, payment);

    SimplifiedDebtsResponse response =
        read(f, "/debt-simplification", SimplifiedDebtsResponse.class);

    assertThat(writeCommitted).isTrue();
    assertSettles(f.balances(400, -200, -200), response.transactions());
    assertSettles(
        afterCommit, read(f, "/debt-simplification", SimplifiedDebtsResponse.class).transactions());
  }

  @ParameterizedTest(name = "dashboard keeps one snapshot across a payment commit: {0}")
  @ValueSource(booleans = {false, true})
  void dashboardSharesOneSnapshotAcrossGroupsTotalsPeopleSettlementsAndActivity(boolean payment)
      throws Exception {
    Fixture f = fixture();
    ExpenseResponse initial = expense(f, f.groupId(), f.owner().user().id(), 600);
    UUID otherGroup = group(f.owner(), f.bob(), f.carol());
    commitBetweenPairwiseAggregates(
        f, payment, () -> expense(f, otherGroup, f.owner().user().id(), 300));

    DashboardResponse response = dashboard(f);

    assertThat(writeCommitted).isTrue();
    assertThat(response.totalNetCents()).isEqualTo(400);
    assertThat(response.owedCents()).isEqualTo(400);
    assertThat(response.oweCents()).isZero();
    assertThat(response.owedPeopleCount()).isEqualTo(2);
    assertThat(response.owePeopleCount()).isZero();
    assertThat(response.groupCount()).isEqualTo(2);
    assertThat(pairwise(response.people()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(f.bob().user().id(), 200L, f.carol().user().id(), 200L));
    assertThat(response.settlements())
        .hasSize(2)
        .allSatisfy(
            s -> {
              assertThat(s.groupId()).isEqualTo(f.groupId());
              assertThat(s.netCents()).isEqualTo(200);
            });
    assertThat(response.groups())
        .hasSize(2)
        .allSatisfy(
            g -> {
              assertThat(g.memberCount()).isEqualTo(3);
              assertThat(g.youOweCents()).isZero();
              assertThat(g.totalSpentCents()).isEqualTo(g.id().equals(f.groupId()) ? 600 : 0);
              assertThat(g.youAreOwedCents()).isEqualTo(g.id().equals(f.groupId()) ? 400 : 0);
              assertThat(g.netCents()).isEqualTo(g.youAreOwedCents());
            });
    assertThat(response.activity())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.id()).isEqualTo(initial.id());
              assertThat(a.amountCents()).isEqualTo(600);
              assertThat(a.viewerDeltaCents()).isEqualTo(400);
            });
    DashboardResponse fresh = dashboard(f);
    assertThat(fresh.totalNetCents()).isEqualTo(payment ? 540 : 650);
    assertThat(fresh.activity()).hasSize(4);
  }

  @Test
  void dashboardActivityUsesTheSameExpenseRevisionAsItsSharesAndCard() throws Exception {
    Fixture f = fixture();
    ExpenseResponse initial = expense(f, f.groupId(), f.owner().user().id(), 600);
    doAnswer(
            afterReadCommit(
                () ->
                    expenseService.updateExpense(
                        f.owner().user().id(),
                        f.groupId(),
                        initial.id(),
                        new CreateExpenseRequest(
                            "Edited during read", 900, f.owner().user().id(), null))))
        .when(participantRepository)
        .findSharesByUserAndGroups(f.owner().user().id(), List.of(f.groupId()));

    DashboardResponse response = dashboard(f);

    assertThat(writeCommitted).isTrue();
    assertThat(response.groups())
        .singleElement()
        .satisfies(g -> assertThat(g.totalSpentCents()).isEqualTo(600));
    assertThat(response.activity())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.amountCents()).isEqualTo(600);
              assertThat(a.viewerDeltaCents()).isEqualTo(400);
            });
    assertThat(dashboard(f).activity())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.amountCents()).isEqualTo(900);
              assertThat(a.viewerDeltaCents()).isEqualTo(600);
            });
  }

  @Test
  void memberRemovalDoesNotMistakeMixedPaymentAggregatesForASettledBalance() throws Exception {
    Fixture f = fixture();
    expense(f, f.groupId(), f.bob().user().id(), 600);
    // Bob is owed 400 before this atomic commit and 100 afterwards, never zero.
    CountDownLatch paymentsWritten = new CountDownLatch(1);
    CountDownLatch commitPayments = new CountDownLatch(1);
    AtomicInteger writerPid = new AtomicInteger();
    try (var requests = Executors.newFixedThreadPool(2)) {
      var write =
          requests.submit(
              () -> {
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        ignored -> {
                          payment(f, f.bob().user().id(), f.owner().user().id(), 100);
                          payment(f, f.owner().user().id(), f.bob().user().id(), 400);
                          writerPid.set(
                              jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                          paymentsWritten.countDown();
                          try {
                            assertThat(commitPayments.await(20, TimeUnit.SECONDS)).isTrue();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                          }
                        });
                writeCommitted.set(true);
              });
      java.util.concurrent.Future<org.springframework.test.web.servlet.MvcResult> removal = null;
      try {
        assertThat(paymentsWritten.await(20, TimeUnit.SECONDS)).isTrue();
        removal =
            requests.submit(
                () ->
                    mockMvc
                        .perform(
                            delete("/api/groups/" + f.groupId() + "/members/" + f.bob().user().id())
                                .header(HttpHeaders.AUTHORIZATION, bearer(f.owner().accessToken())))
                        .andReturn());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean blocked;
        do {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                      Boolean.class,
                      writerPid.get()));
          if (blocked) break;
          Thread.yield();
        } while (System.nanoTime() < deadline);
        assertThat(blocked).as("removal waits for the atomic payment commit").isTrue();
      } finally {
        commitPayments.countDown();
      }
      write.get(20, TimeUnit.SECONDS);
      assertThat(removal.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
    }

    assertThat(writeCommitted).isTrue();
    assertThat(membershipRepository.existsByGroupIdAndUserId(f.groupId(), f.bob().user().id()))
        .isTrue();
    assertBalances(read(f, "/balances", GroupBalancesResponse.class), f.balances(100, 100, -200));
  }

  @Test
  void balanceEndpointsKeep403Before404ForNonMembers() throws Exception {
    Fixture f = fixture();
    AuthResponse outsider = register(UUID.randomUUID() + "@example.com", "password123", "Outsider");
    for (UUID groupId : List.of(f.groupId(), UUID.randomUUID())) {
      for (String suffix : List.of("/balances", "/balances/mine", "/debt-simplification")) {
        mockMvc
            .perform(
                get("/api/groups/" + groupId + suffix)
                    .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
            .andExpect(status().isForbidden());
      }
    }
  }

  private Map<UUID, Long> commitBetweenBalanceAggregates(Fixture f, boolean payment) {
    if (payment) {
      doAnswer(afterReadCommit(() -> payment(f, f.bob().user().id(), f.owner().user().id(), 200)))
          .when(paymentRepository)
          .sumPaidByGroup(f.groupId());
      return f.balances(200, 0, -200);
    }
    doAnswer(afterReadCommit(() -> expense(f, f.groupId(), f.owner().user().id(), 300)))
        .when(expenseRepository)
        .sumPaidByGroup(f.groupId());
    return f.balances(600, -300, -300);
  }

  private void commitBetweenPairwiseAggregates(Fixture f, boolean payment, Runnable extraWrite) {
    if (payment) {
      doAnswer(
              afterReadCommit(
                  () -> {
                    payment(f, f.owner().user().id(), f.bob().user().id(), 40);
                    payment(f, f.bob().user().id(), f.owner().user().id(), 100);
                    extraWrite.run();
                  }))
          .when(paymentRepository)
          .sumPaidByPair(f.groupId());
    } else {
      doAnswer(
              afterReadCommit(
                  () -> {
                    for (var paid :
                        Map.of(f.owner().user().id(), 300L, f.bob().user().id(), 200L).entrySet()) {
                      expenseService.createExpense(
                          f.owner().user().id(),
                          f.groupId(),
                          new CreateExpenseRequest(
                              "Concurrent expense",
                              paid.getValue(),
                              paid.getKey(),
                              List.of(f.owner().user().id(), f.bob().user().id())));
                    }
                    extraWrite.run();
                  }))
          .when(participantRepository)
          .sumOwedByPair(f.groupId());
    }
  }

  private Answer<Object> afterReadCommit(Runnable write) {
    return invocation -> {
      // Spring Data repositories are JDK proxies; the spy's default answer delegates to
      // the actual repository (callRealMethod cannot invoke an abstract query method).
      Object rows =
          mockingDetails(invocation.getMock())
              .getMockCreationSettings()
              .getDefaultAnswer()
              .answer(invocation);
      if (writeStarted.compareAndSet(false, true)) {
        // A different thread/connection commits before the reader's next query. No sleeps,
        // replacement query results, or test-managed reader transaction can mask the race.
        CompletableFuture.runAsync(
                () ->
                    new TransactionTemplate(transactionManager)
                        .executeWithoutResult(ignored -> write.run()))
            .get(20, TimeUnit.SECONDS);
        writeCommitted.set(true);
      }
      return rows;
    };
  }

  private Fixture fixture() throws Exception {
    String id = UUID.randomUUID().toString();
    AuthResponse owner = register(id + "-a@example.com", "password123", "Alice");
    AuthResponse bob = register(id + "-b@example.com", "password123", "Bob");
    AuthResponse carol = register(id + "-c@example.com", "password123", "Carol");
    return new Fixture(owner, bob, carol, group(owner, bob, carol));
  }

  private UUID group(AuthResponse owner, AuthResponse bob, AuthResponse carol) {
    UUID groupId =
        groupService.createGroup(owner.user().id(), new CreateGroupRequest("Snapshot")).id();
    groupService.addMember(owner.user().id(), groupId, bob.user().email());
    groupService.addMember(owner.user().id(), groupId, carol.user().email());
    groupService.updateGroup(
        owner.user().id(),
        groupId,
        new com.spliteasy.dto.group.UpdateGroupRequest(null, null, false));
    return groupId;
  }

  private ExpenseResponse expense(Fixture f, UUID groupId, UUID payer, long cents) {
    return expenseService.createExpense(
        f.owner().user().id(),
        groupId,
        new CreateExpenseRequest("Snapshot expense", cents, payer, null));
  }

  private void payment(Fixture f, UUID payer, UUID payee, long cents) {
    paymentService.recordPayment(
        f.owner().user().id(), f.groupId(), new RecordPaymentRequest(payer, payee, cents));
  }

  private <T> T read(Fixture f, String suffix, Class<T> type) throws Exception {
    return getJson(f, "/api/groups/" + f.groupId() + suffix, type);
  }

  private DashboardResponse dashboard(Fixture f) throws Exception {
    return getJson(f, "/api/dashboard", DashboardResponse.class);
  }

  private <T> T getJson(Fixture f, String path, Class<T> type) throws Exception {
    var result =
        mockMvc
            .perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(f.owner().accessToken())))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), type);
  }

  private Map<UUID, Long> pairwise(List<PersonBalance> people) {
    return people.stream().collect(Collectors.toMap(p -> p.user().id(), PersonBalance::netCents));
  }

  private void assertBalances(GroupBalancesResponse response, Map<UUID, Long> expected) {
    assertThat(
            response.balances().stream()
                .map(b -> BigInteger.valueOf(b.netCents()))
                .reduce(BigInteger.ZERO, BigInteger::add))
        .isZero();
    assertThat(
            response.balances().stream()
                .collect(Collectors.toMap(b -> b.user().id(), b -> b.netCents())))
        .containsExactlyInAnyOrderEntriesOf(expected);
  }

  private void assertSettles(Map<UUID, Long> balances, List<SuggestedTransaction> transfers) {
    Map<UUID, BigInteger> remaining = new HashMap<>();
    balances.forEach((id, net) -> remaining.put(id, BigInteger.valueOf(net)));
    assertThat(transfers).hasSizeLessThanOrEqualTo(balances.size() - 1);
    for (SuggestedTransaction transfer : transfers) {
      assertThat(transfer.amountCents()).isPositive();
      assertThat(transfer.from().id()).isNotEqualTo(transfer.to().id());
      BigInteger amount = BigInteger.valueOf(transfer.amountCents());
      remaining.compute(transfer.from().id(), (id, net) -> net.add(amount));
      remaining.compute(transfer.to().id(), (id, net) -> net.subtract(amount));
    }
    assertThat(remaining.values()).allSatisfy(net -> assertThat(net).isZero());
  }

  private record Fixture(AuthResponse owner, AuthResponse bob, AuthResponse carol, UUID groupId) {
    Map<UUID, Long> balances(long aliceNet, long bobNet, long carolNet) {
      return Map.of(
          owner.user().id(), aliceNet, bob.user().id(), bobNet, carol.user().id(), carolNet);
    }
  }
}
