package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.balance.GroupBalancesResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.GroupRepository;
import com.spliteasy.repository.UserRepository;
import com.spliteasy.service.MembershipGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Independent real PostgreSQL transactions; probes only coordinate request ordering. */
@AutoConfigureMockMvc
class FinancialMembershipRaceIntegrationTest extends AbstractIntegrationTest {
  @MockitoSpyBean MembershipGuard membershipGuard;
  @MockitoSpyBean UserRepository userRepository;
  @MockitoSpyBean GroupRepository groupRepository;
  @MockitoSpyBean GroupMembershipRepository membershipRepository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void requesterWhoLeavesAfterAuthorizationCannotCommitAnExpense() throws Exception {
    Fixture f = fixture();
    CountDownLatch authorized = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    AtomicReference<Thread> writer = new AtomicReference<>();
    AtomicBoolean paused = new AtomicBoolean();
    doAnswer(
            call -> {
              call.callRealMethod();
              if (Thread.currentThread() == writer.get() && paused.compareAndSet(false, true)) {
                authorized.countDown();
                assertThat(resume.await(20, TimeUnit.SECONDS)).isTrue();
              }
              return null;
            })
        .when(membershipGuard)
        .requireMember(eq(f.groupId()), eq(f.b().user().id()));

    try (var requests = Executors.newSingleThreadExecutor()) {
      var write =
          requests.submit(
              () -> {
                writer.set(Thread.currentThread());
                return mockMvc
                    .perform(
                        auth(
                            jsonPost(
                                path(f, "/expenses"),
                                new CreateExpenseRequest(
                                    "Stale requester",
                                    1000,
                                    f.a().user().id(),
                                    List.of(f.a().user().id()))),
                            f.b()))
                    .andReturn();
              });
      try {
        assertThat(authorized.await(20, TimeUnit.SECONDS)).isTrue();
        mockMvc
            .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
            .andExpect(status().isNoContent());
      } finally {
        resume.countDown();
      }
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
    }
    assertThat(read(f, "/expenses", Object[].class)).isEmpty();
    assertThat(read(f, "/payments", Object[].class)).isEmpty();
    assertThat(read(f, "", GroupResponse.class).members())
        .extracting(u -> u.id())
        .containsExactly(f.a().user().id());
    assertThat(read(f, "/balances", GroupBalancesResponse.class).balances())
        .singleElement()
        .satisfies(b -> assertThat(b.netCents()).isZero());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void financialWriteFirstMakesRemovalObserveItsCommittedObligations(boolean payment)
      throws Exception {
    Fixture f = fixture();
    CountDownLatch validated = new CountDownLatch(1);
    CountDownLatch finishWrite = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(validated, finishWrite, blocker))
        .when(userRepository)
        .findAllById(any());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var write =
          requests.submit(() -> mockMvc.perform(auth(financial(f, payment), f.a())).andReturn());
      java.util.concurrent.Future<MvcResult> removal = null;
      try {
        assertThat(validated.await(20, TimeUnit.SECONDS)).isTrue();
        removal =
            requests.submit(
                () ->
                    mockMvc
                        .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.a()))
                        .andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        finishWrite.countDown();
      }
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
      assertThat(removal.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
    }
    assertThat(read(f, "", GroupResponse.class).members()).hasSize(2);
    assertThat(read(f, payment ? "/payments" : "/expenses", Object[].class)).hasSize(1);
    var ledger = read(f, "/balances", GroupBalancesResponse.class).balances();
    assertThat(ledger.stream().mapToLong(b -> b.netCents()).sum()).isZero();
    assertThat(ledger.stream().collect(Collectors.toMap(b -> b.user().id(), b -> b.netCents())))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                f.a().user().id(),
                payment ? -1000L : 500L,
                f.b().user().id(),
                payment ? 1000L : -500L));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void removalFirstRejectsAnAlreadyWaitingWriteForTheInactiveCounterparty(boolean payment)
      throws Exception {
    Fixture f = fixture();
    CountDownLatch removing = new CountDownLatch(1);
    CountDownLatch finishRemoval = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(removing, finishRemoval, blocker))
        .when(membershipRepository)
        .deleteByGroupIdAndUserId(f.groupId(), f.b().user().id());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var removal =
          requests.submit(
              () ->
                  mockMvc
                      .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
                      .andReturn());
      java.util.concurrent.Future<MvcResult> write = null;
      try {
        assertThat(removing.await(20, TimeUnit.SECONDS)).isTrue();
        write =
            requests.submit(() -> mockMvc.perform(auth(financial(f, payment), f.a())).andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        finishRemoval.countDown();
      }
      assertThat(removal.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(400);
    }
    assertThat(read(f, "/expenses", Object[].class)).isEmpty();
    assertThat(read(f, "/payments", Object[].class)).isEmpty();
    assertThat(read(f, "", GroupResponse.class).members())
        .extracting(u -> u.id())
        .containsExactly(f.a().user().id());
    assertThat(read(f, "/balances", GroupBalancesResponse.class).balances())
        .singleElement()
        .satisfies(b -> assertThat(b.netCents()).isZero());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "createExpense",
        "updateExpense",
        "deleteExpense",
        "restoreExpense",
        "payment",
        "addMember",
        "removeMember",
        "mode"
      })
  void selfLeaveRevokesAnAlreadyWaitingRequestersWrite(String mutation) throws Exception {
    Fixture f = fixture();
    var c = register(UUID.randomUUID() + "-c@example.com", "password123", "Carol");
    var d = register(UUID.randomUUID() + "-d@example.com", "password123", "Dan");
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(c.user().email())), f.a()),
        200,
        GroupResponse.class);
    ExpenseResponse original =
        body(
            auth(
                jsonPost(
                    path(f, "/expenses"),
                    new CreateExpenseRequest(
                        "Original solo", 1000, f.a().user().id(), List.of(f.a().user().id()))),
                f.a()),
            201,
            ExpenseResponse.class);
    if (mutation.equals("restoreExpense")) {
      mockMvc
          .perform(
              auth(
                  delete(path(f, "/expenses/" + original.id()))
                      .param("expectedVersion", Long.toString(original.version())),
                  f.a()))
          .andExpect(status().isNoContent());
    }
    long restoreVersion =
        mutation.equals("restoreExpense")
            ? read(
                f,
                "/expenses/" + original.id() + "/history",
                com.spliteasy.dto.expense.ExpenseRevision[].class)[1]
                .version()
            : 0;
    Object[] before = read(f, "/expenses", Object[].class);
    var changed =
        new CreateExpenseRequest(
            "Stale requester change", 3000, f.a().user().id(), List.of(f.a().user().id()));
    MockHttpServletRequestBuilder request =
        switch (mutation) {
          case "createExpense" -> jsonPost(path(f, "/expenses"), changed);
          case "updateExpense" ->
              put(path(f, "/expenses/" + original.id()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(changed));
          case "deleteExpense" -> delete(path(f, "/expenses/" + original.id()));
          case "restoreExpense" ->
              jsonPost(
                  path(f, "/expenses/" + original.id() + "/restore"),
                  Map.of("expectedVersion", restoreVersion));
          case "payment" ->
              jsonPost(
                  path(f, "/payments"),
                  new RecordPaymentRequest(f.a().user().id(), c.user().id(), 1000));
          case "addMember" -> jsonPost(path(f, "/members"), new AddMemberRequest(d.user().email()));
          case "removeMember" -> delete(path(f, "/members/" + c.user().id()));
          case "mode" ->
              patch(path(f, ""))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"simplifyDebts\":false}");
          default -> throw new IllegalArgumentException(mutation);
        };
    CountDownLatch removing = new CountDownLatch(1);
    CountDownLatch finishRemoval = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(removing, finishRemoval, blocker))
        .when(membershipRepository)
        .deleteByGroupIdAndUserId(f.groupId(), f.b().user().id());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var removal =
          requests.submit(
              () ->
                  mockMvc
                      .perform(auth(delete(path(f, "/members/" + f.b().user().id())), f.b()))
                      .andReturn());
      java.util.concurrent.Future<MvcResult> write = null;
      try {
        assertThat(removing.await(20, TimeUnit.SECONDS)).isTrue();
        write = requests.submit(() -> mockMvc.perform(auth(request, f.b())).andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        finishRemoval.countDown();
      }
      assertThat(removal.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
    }
    assertThat(read(f, "/expenses", Object[].class)).containsExactly(before);
    assertThat(read(f, "/payments", Object[].class)).isEmpty();
    assertThat(read(f, "", GroupResponse.class).members())
        .extracting(u -> u.id())
        .containsExactlyInAnyOrder(f.a().user().id(), c.user().id());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void modeAndSimplifiedIntermediaryDepartureSerializeInBothOrders(boolean modeFirst)
      throws Exception {
    Fixture f = fixture();
    var c = register(UUID.randomUUID() + "-c@example.com", "password123", "Carol");
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(c.user().email())), f.a()),
        200,
        GroupResponse.class);
    body(
        auth(
            jsonPost(
                path(f, "/expenses"),
                new CreateExpenseRequest(
                    "A owes B", 1000, f.b().user().id(), List.of(f.a().user().id()))),
            f.a()),
        201,
        ExpenseResponse.class);
    body(
        auth(
            jsonPost(
                path(f, "/expenses"),
                new CreateExpenseRequest(
                    "B owes C", 1000, c.user().id(), List.of(f.b().user().id()))),
            f.a()),
        201,
        ExpenseResponse.class);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(locked, release, blocker))
        .when(groupRepository)
        .findByIdForUpdate(f.groupId());
    MockHttpServletRequestBuilder mode =
        auth(
            patch(path(f, ""))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"simplifyDebts\":false}"),
            f.a());
    MockHttpServletRequestBuilder leave =
        auth(delete(path(f, "/members/" + f.b().user().id())), f.b());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var first = requests.submit(() -> mockMvc.perform(modeFirst ? mode : leave).andReturn());
      java.util.concurrent.Future<MvcResult> second = null;
      try {
        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();
        second = requests.submit(() -> mockMvc.perform(modeFirst ? leave : mode).andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        release.countDown();
      }
      assertThat(first.get(20, TimeUnit.SECONDS).getResponse().getStatus())
          .isEqualTo(modeFirst ? 200 : 204);
      assertThat(second.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
    }
    GroupResponse group = read(f, "", GroupResponse.class);
    assertThat(group.simplifyDebts()).isEqualTo(!modeFirst);
    assertThat(group.members()).hasSize(modeFirst ? 3 : 2);
    assertThat(read(f, "/expenses", Object[].class)).hasSize(2);
    assertThat(read(f, "/payments", Object[].class)).isEmpty();
    assertThat(read(f, "/balances/obligations", Object[].class)).hasSize(2);
    assertThat(read(f, "/balances/current-obligations", Object[].class)).hasSize(modeFirst ? 2 : 1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void modeWaitsForFinancialWritesBeforeProjectingTheirCommit(boolean payment) throws Exception {
    Fixture f = fixture();
    CountDownLatch validated = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(validated, release, blocker)).when(userRepository).findAllById(any());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var write =
          requests.submit(() -> mockMvc.perform(auth(financial(f, payment), f.a())).andReturn());
      java.util.concurrent.Future<MvcResult> mode = null;
      try {
        assertThat(validated.await(20, TimeUnit.SECONDS)).isTrue();
        mode =
            requests.submit(
                () ->
                    mockMvc
                        .perform(
                            auth(
                                patch(path(f, ""))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"simplifyDebts\":false}"),
                                f.a()))
                        .andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        release.countDown();
      }
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
      assertThat(mode.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    }
    assertThat(read(f, "", GroupResponse.class).simplifyDebts()).isFalse();
    assertThat(read(f, payment ? "/payments" : "/expenses", Object[].class)).hasSize(1);
    assertThat(read(f, "/balances/current-obligations", Object[].class)).hasSize(1);
  }

  @Test
  void concurrentGroupDeletionRechecksMembershipBeforeChoosingNotFound() throws Exception {
    Fixture f = fixture();
    CountDownLatch deleting = new CountDownLatch(1);
    CountDownLatch finishDelete = new CountDownLatch(1);
    AtomicInteger blocker = new AtomicInteger();
    doAnswer(holdAfterQuery(deleting, finishDelete, blocker)).when(groupRepository).delete(any());
    try (var requests = Executors.newFixedThreadPool(2)) {
      var deletion =
          requests.submit(() -> mockMvc.perform(auth(delete(path(f, "")), f.a())).andReturn());
      java.util.concurrent.Future<MvcResult> write = null;
      try {
        assertThat(deleting.await(20, TimeUnit.SECONDS)).isTrue();
        write =
            requests.submit(() -> mockMvc.perform(auth(financial(f, false), f.a())).andReturn());
        awaitBlockedBy(blocker.get());
      } finally {
        finishDelete.countDown();
      }
      assertThat(deletion.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(204);
      assertThat(write.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(403);
    }
    for (String suffix : List.of("", "/balances", "/expenses", "/payments")) {
      mockMvc.perform(auth(get(path(f, suffix)), f.a())).andExpect(status().isForbidden());
    }
    assertThat(body(auth(get("/api/groups"), f.a()), 200, Object[].class)).isEmpty();
  }

  private MockHttpServletRequestBuilder financial(Fixture f, boolean payment) throws Exception {
    return payment
        ? jsonPost(
            path(f, "/payments"),
            new RecordPaymentRequest(f.b().user().id(), f.a().user().id(), 1000))
        : jsonPost(
            path(f, "/expenses"),
            new CreateExpenseRequest(
                "Concurrent expense",
                1000,
                f.a().user().id(),
                List.of(f.a().user().id(), f.b().user().id())));
  }

  private Answer<Object> holdAfterQuery(
      CountDownLatch ready, CountDownLatch release, AtomicInteger blocker) {
    AtomicBoolean held = new AtomicBoolean();
    return call -> {
      Object result =
          mockingDetails(call.getMock()).getMockCreationSettings().getDefaultAnswer().answer(call);
      if (held.compareAndSet(false, true)) {
        blocker.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
        ready.countDown();
        assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
      }
      return result;
    };
  }

  private void awaitBlockedBy(int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    boolean blocked;
    do {
      blocked =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                  Boolean.class,
                  blockerPid));
      if (blocked) break;
      Thread.yield();
    } while (System.nanoTime() < deadline);
    assertThat(blocked)
        .as("second HTTP transaction waits for the first group's PostgreSQL row lock")
        .isTrue();
  }

  private Fixture fixture() throws Exception {
    String id = UUID.randomUUID().toString();
    var a = register(id + "-a@example.com", "password123", "Alice");
    var b = register(id + "-b@example.com", "password123", "Bob");
    GroupResponse group =
        body(
            auth(jsonPost("/api/groups", new CreateGroupRequest("Races")), a),
            201,
            GroupResponse.class);
    Fixture f = new Fixture(a, b, group.id());
    body(
        auth(jsonPost(path(f, "/members"), new AddMemberRequest(b.user().email())), a),
        200,
        GroupResponse.class);
    return f;
  }

  private <T> T read(Fixture f, String suffix, Class<T> type) throws Exception {
    return body(auth(get(path(f, suffix)), f.a()), 200, type);
  }

  private <T> T body(MockHttpServletRequestBuilder request, int status, Class<T> type)
      throws Exception {
    MvcResult response = mockMvc.perform(request).andExpect(status().is(status)).andReturn();
    return objectMapper.readValue(response.getResponse().getContentAsString(), type);
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
