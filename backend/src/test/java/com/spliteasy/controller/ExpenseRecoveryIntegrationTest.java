package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class ExpenseRecoveryIntegrationTest extends AbstractIntegrationTest {
  private record Ledger(AuthResponse alice, AuthResponse bob, String path) {}

  private Ledger ledger() throws Exception {
    String prefix = UUID.randomUUID().toString();
    var alice = register(prefix + "a@example.test", "password123", "Alice");
    var bob = register(prefix + "b@example.test", "password123", "Bob");
    var group = send(alice, jsonPost("/api/groups", Map.of("name", "Recovery")), 201);
    String path = "/api/groups/" + group.path("id").asText();
    send(alice, jsonPost(path + "/members", Map.of("email", bob.user().email())), 200);
    return new Ledger(alice, bob, path);
  }

  private JsonNode send(AuthResponse actor, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(actor.accessToken()))
                    .header("X-Ledger-Version", "2"))
            .andExpect(status().is(expected))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return body.isEmpty() ? objectMapper.readTree("null") : objectMapper.readTree(body);
  }

  private Map<String, Object> expense(Ledger f, String currency, long amount) {
    return new HashMap<>(
        Map.of(
            "description",
            "Dinner",
            "currency",
            currency,
            "amountMinor",
            amount,
            "paidByUserId",
            f.bob().user().id(),
            "splitType",
            "WEIGHTS",
            "splits",
            List.of(
                Map.of("userId", f.alice().user().id(), "value", 1),
                Map.of("userId", f.bob().user().id(), "value", 2)),
            "spentOn",
            "2026-10-20"));
  }

  @Test
  void acceptedCreationAndReplacementKeepAttributableFinancialSnapshots() throws Exception {
    var f = ledger();
    var create = expense(f, "KWD", 1234);
    var operation = UUID.randomUUID();
    create.put("operationId", operation);
    Instant started = Instant.now();
    var original = send(f.alice(), jsonPost(f.path() + "/expenses", create), 201);
    String path = f.path() + "/expenses/" + original.path("id").asText();
    var history = send(f.bob(), get(path + "/history"), 200);
    assertThat(history.size()).isEqualTo(1);
    assertThat(history.get(0).path("action").asText()).isEqualTo("CREATED");
    assertThat(history.get(0).path("actor").path("id").asText())
        .isEqualTo(f.alice().user().id().toString());
    assertThat(history.get(0).path("operationId").asText()).isEqualTo(operation.toString());
    assertThat(Instant.parse(history.get(0).path("recordedAt").asText())).isAfterOrEqualTo(started);
    assertThat(history.get(0).path("before").isNull()).isTrue();
    assertThat(history.get(0).path("after")).isEqualTo(original);

    var persistedOriginal = send(f.alice(), get(path), 200);
    var replacement = expense(f, "KWD", 1501);
    replacement.put("description", "Updated dinner");
    replacement.put("expectedVersion", original.path("version").asLong());
    var editOperation = UUID.randomUUID();
    replacement.put("operationId", editOperation);
    var updated =
        send(
            f.bob(),
            put(path).contentType(MediaType.APPLICATION_JSON).content(json(replacement)),
            200);
    history = send(f.alice(), get(path + "/history"), 200);
    assertThat(history.size()).isEqualTo(2);
    var revision = history.get(1);
    assertThat(revision.path("action").asText()).isEqualTo("REPLACED");
    assertThat(revision.path("actor").path("id").asText())
        .isEqualTo(f.bob().user().id().toString());
    assertThat(revision.path("operationId").asText()).isEqualTo(editOperation.toString());
    assertThat(revision.path("before")).isEqualTo(persistedOriginal);
    assertThat(revision.path("after")).isEqualTo(updated);
    assertThat(revision.path("version").asLong()).isEqualTo(updated.path("version").asLong());
    assertThat(updated.path("version").asLong()).isGreaterThan(original.path("version").asLong());
    assertThat(updated.path("amountCents").isNull()).isTrue();
    assertThat(updated.path("splits")).isEqualTo(original.path("splits"));
  }

  @Test
  void deletingRetainsHistoryButExcludesTheExpenseFromEveryFinancialRead() throws Exception {
    var f = ledger();
    send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "JPY", 900)), 201);
    var expected = new HashMap<String, JsonNode>();
    for (String route :
        List.of(
            "/expenses",
            "/balances",
            "/balances/mine",
            "/balances/obligations",
            "/balances/current-obligations",
            "/debt-simplification")) {
      expected.put(route, send(f.alice(), get(f.path() + route), 200));
    }
    var dashboard = send(f.alice(), get("/api/dashboard"), 200);
    var original = send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "KWD", 1234)), 201);
    String path = f.path() + "/expenses/" + original.path("id").asText();
    var before = send(f.alice(), get(path), 200);
    var operation = UUID.randomUUID();
    send(
        f.bob(),
        delete(path)
            .param("expectedVersion", original.path("version").asText())
            .param("operationId", operation.toString()),
        204);
    send(f.alice(), get(path), 404);
    var history = send(f.alice(), get(path + "/history"), 200);
    assertThat(history.size()).isEqualTo(2);
    var deleted = history.get(1);
    assertThat(deleted.path("action").asText()).isEqualTo("DELETED");
    assertThat(deleted.path("actor").path("id").asText()).isEqualTo(f.bob().user().id().toString());
    assertThat(deleted.path("operationId").asText()).isEqualTo(operation.toString());
    assertThat(deleted.path("before")).isEqualTo(before);
    assertThat(deleted.path("version").asLong()).isGreaterThan(original.path("version").asLong());
    assertThat(deleted.path("after").path("participants")).isEqualTo(before.path("participants"));
    var recoverable = send(f.alice(), get(f.path() + "/expenses/deleted"), 200);
    assertThat(recoverable.size()).isEqualTo(1);
    assertThat(recoverable.get(0).path("expense")).isEqualTo(deleted.path("after"));
    assertThat(Instant.parse(recoverable.get(0).path("deletedAt").asText()))
        .isBeforeOrEqualTo(Instant.now());
    for (var route : expected.entrySet()) {
      assertThat(send(f.alice(), get(f.path() + route.getKey()), 200)).isEqualTo(route.getValue());
    }
    assertThat(send(f.alice(), get("/api/dashboard"), 200)).isEqualTo(dashboard);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"USD", "EUR", "GBP", "INR", "JPY", "KWD"})
  void versionedRestoreReinstatesOriginalMoneyOnceWithoutReplayingCreate(String currency)
      throws Exception {
    var f = ledger();
    var request = expense(f, currency, 1234);
    request.put("operationId", UUID.randomUUID());
    var original = send(f.alice(), jsonPost(f.path() + "/expenses", request), 201);
    String path = f.path() + "/expenses/" + original.path("id").asText();
    var persisted = send(f.alice(), get(path), 200);
    var balances = send(f.alice(), get(f.path() + "/balances"), 200);
    send(f.alice(), delete(path).param("expectedVersion", original.path("version").asText()), 204);
    var deleted = send(f.alice(), get(f.path() + "/expenses/deleted"), 200).get(0).path("expense");
    // An immutable create receipt never restores a deleted live expense.
    assertThat(send(f.alice(), jsonPost(f.path() + "/expenses", request), 201)).isEqualTo(original);
    send(f.alice(), get(path), 404);
    send(f.alice(), jsonPost(path + "/restore", Map.of()), 400);
    send(
        f.alice(),
        jsonPost(path + "/restore", Map.of("expectedVersion", original.path("version").asLong())),
        409);
    var operation = UUID.randomUUID();
    var restore =
        Map.of("expectedVersion", deleted.path("version").asLong(), "operationId", operation);
    var restored = send(f.bob(), jsonPost(path + "/restore", restore), 200);
    assertThat(restored.path("version").asLong()).isGreaterThan(deleted.path("version").asLong());
    for (String field :
        List.of(
            "description",
            "currency",
            "amountMinor",
            "amountCents",
            "paidBy",
            "participants",
            "participantUserIds",
            "splits",
            "splitType",
            "createdAt",
            "spentOn")) {
      assertThat(restored.path(field)).as(field).isEqualTo(persisted.path(field));
    }
    assertThat(send(f.alice(), get(f.path() + "/balances"), 200)).isEqualTo(balances);
    assertThat(send(f.alice(), get(f.path() + "/expenses/deleted"), 200).isEmpty()).isTrue();
    send(f.bob(), jsonPost(path + "/restore", restore), 409);
    var history = send(f.alice(), get(path + "/history"), 200);
    assertThat(history.size()).isEqualTo(3);
    var revision = history.get(2);
    assertThat(revision.path("action").asText()).isEqualTo("RESTORED");
    assertThat(revision.path("actor").path("id").asText())
        .isEqualTo(f.bob().user().id().toString());
    assertThat(revision.path("operationId").asText()).isEqualTo(operation.toString());
    assertThat(revision.path("before")).isEqualTo(deleted);
    assertThat(revision.path("after")).isEqualTo(restored);
    assertThat(send(f.alice(), jsonPost(f.path() + "/expenses", request), 201)).isEqualTo(original);
    assertThat(send(f.alice(), get(path), 200)).isEqualTo(restored);
  }

  @Test
  void rejectedAndUnauthorizedRecoveryLeavesHistoryAndMoneyUntouched() throws Exception {
    var f = ledger();
    var outsider = register(UUID.randomUUID() + "@example.test", "password123", "Outsider");
    var created = send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "KWD", 1234)), 201);
    String path = f.path() + "/expenses/" + created.path("id").asText();
    var history = send(f.alice(), get(path + "/history"), 200);
    var invalid = expense(f, "KWD", 0);
    invalid.put("expectedVersion", created.path("version").asLong());
    send(f.alice(), put(path).contentType(MediaType.APPLICATION_JSON).content(json(invalid)), 400);
    send(f.alice(), delete(path).param("expectedVersion", "999"), 409);
    assertThat(send(f.alice(), get(path + "/history"), 200)).isEqualTo(history);
    send(f.alice(), delete(path).param("expectedVersion", created.path("version").asText()), 204);
    var recoverable = send(f.alice(), get(f.path() + "/expenses/deleted"), 200);
    long version = recoverable.get(0).path("expense").path("version").asLong();
    var command = Map.of("expectedVersion", version);
    history = send(f.alice(), get(path + "/history"), 200);
    for (String candidate :
        List.of(
            path,
            f.path() + "/expenses/" + UUID.randomUUID(),
            "/api/groups/" + UUID.randomUUID() + "/expenses/" + UUID.randomUUID())) {
      send(outsider, get(candidate + "/history"), 403);
      send(outsider, jsonPost(candidate + "/restore", command), 403);
    }
    send(outsider, get(f.path() + "/expenses/deleted"), 403);
    for (var request :
        List.of(
            get(path + "/history"),
            get(f.path() + "/expenses/deleted"),
            jsonPost(path + "/restore", command))) {
      mockMvc
          .perform(request.header("Authorization", bearer(f.alice().accessToken())))
          .andExpect(status().isConflict());
    }
    // Deletion permits settled departures; retained participation grants no access or safe restore.
    send(f.alice(), delete(f.path() + "/members/" + f.bob().user().id()), 204);
    send(f.bob(), get(path + "/history"), 403);
    send(f.bob(), get(f.path() + "/expenses/deleted"), 403);
    var failure = send(f.alice(), jsonPost(path + "/restore", command), 409);
    assertThat(failure.path("message").asText()).contains("Re-add");
    assertThat(send(f.alice(), get(path + "/history"), 200)).isEqualTo(history);
    assertThat(send(f.alice(), get(f.path() + "/expenses/deleted"), 200)).isEqualTo(recoverable);
    var balances = send(f.alice(), get(f.path() + "/balances"), 200).path("balances");
    assertThat(balances.size()).isEqualTo(1);
    assertThat(balances.get(0).path("netMinor").asLong()).isZero();
    send(f.alice(), jsonPost(f.path() + "/members", Map.of("email", f.bob().user().email())), 200);
    send(f.alice(), jsonPost(path + "/restore", command), 200);
    assertThat(send(f.alice(), get(path + "/history"), 200).size()).isEqualTo(3);
  }

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  com.spliteasy.repository.ExpenseRepository expenseRepository;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void concurrentRestoresWaitForTheGroupWriteAndApplyMoneyAndHistoryOnce() throws Exception {
    var f = ledger();
    var created = send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "KWD", 1234)), 201);
    String path = f.path() + "/expenses/" + created.path("id").asText();
    var balances = send(f.alice(), get(f.path() + "/balances"), 200);
    send(f.alice(), delete(path).param("expectedVersion", created.path("version").asText()), 204);
    var deleted = send(f.alice(), get(f.path() + "/expenses/deleted"), 200).get(0).path("expense");
    var command = Map.of("expectedVersion", deleted.path("version").asLong());
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var held = new java.util.concurrent.atomic.AtomicBoolean();
    var blocker = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            call -> {
              var result =
                  org.mockito.Mockito.mockingDetails(call.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(call);
              if (held.compareAndSet(false, true)) {
                blocker.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                locked.countDown();
                assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(expenseRepository)
        .saveAndFlush(org.mockito.ArgumentMatchers.any());
    try (var requests = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = requests.submit(() -> send(f.alice(), jsonPost(path + "/restore", command), 200));
      java.util.concurrent.Future<JsonNode> second = null;
      try {
        assertThat(locked.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        second = requests.submit(() -> send(f.bob(), jsonPost(path + "/restore", command), 409));
        awaitBlockedBy(blocker.get());
      } finally {
        release.countDown();
      }
      var restored = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      second.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(send(f.alice(), get(path), 200)).isEqualTo(restored);
    }
    assertThat(send(f.alice(), get(f.path() + "/balances"), 200)).isEqualTo(balances);
    assertThat(send(f.alice(), get(path + "/history"), 200).size()).isEqualTo(3);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void deleteAndReplacementRaceUsesTheCommittedVersionWithoutChangingOtherCurrencies(
      boolean deleteFirst) throws Exception {
    var f = ledger();
    send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "JPY", 900)), 201);
    var created = send(f.alice(), jsonPost(f.path() + "/expenses", expense(f, "KWD", 1234)), 201);
    String path = f.path() + "/expenses/" + created.path("id").asText();
    var edit = expense(f, "KWD", 1500);
    edit.put("expectedVersion", created.path("version").asLong());
    var deletion = delete(path).param("expectedVersion", created.path("version").asText());
    var replacement = put(path).contentType(MediaType.APPLICATION_JSON).content(json(edit));
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var held = new java.util.concurrent.atomic.AtomicBoolean();
    var blocker = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(
            call -> {
              var result =
                  org.mockito.Mockito.mockingDetails(call.getMock())
                      .getMockCreationSettings()
                      .getDefaultAnswer()
                      .answer(call);
              if (held.compareAndSet(false, true)) {
                blocker.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                locked.countDown();
                assertThat(release.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
              }
              return result;
            })
        .when(expenseRepository)
        .saveAndFlush(org.mockito.ArgumentMatchers.any());
    try (var requests = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          requests.submit(
              () -> send(f.alice(), deleteFirst ? deletion : replacement, deleteFirst ? 204 : 200));
      java.util.concurrent.Future<JsonNode> second = null;
      try {
        assertThat(locked.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        second =
            requests.submit(
                () -> send(f.bob(), deleteFirst ? replacement : deletion, deleteFirst ? 404 : 409));
        awaitBlockedBy(blocker.get());
      } finally {
        release.countDown();
      }
      first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      second.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    var history = send(f.alice(), get(path + "/history"), 200);
    assertThat(history.size()).isEqualTo(2);
    assertThat(history.get(1).path("action").asText())
        .isEqualTo(deleteFirst ? "DELETED" : "REPLACED");
    var balances = send(f.alice(), get(f.path() + "/balances"), 200).path("balances");
    var amounts = new HashMap<String, Long>();
    for (var balance : balances) {
      String key =
          balance.path("currency").asText() + "/" + balance.path("user").path("id").asText();
      amounts.put(key, balance.path("netMinor").asLong());
    }
    assertThat(amounts)
        .containsEntry("JPY/" + f.alice().user().id(), -300L)
        .containsEntry("JPY/" + f.bob().user().id(), 300L);
    if (deleteFirst) {
      send(f.alice(), get(path), 404);
      assertThat(amounts).hasSize(2);
    } else {
      assertThat(send(f.alice(), get(path), 200).path("amountMinor").asLong()).isEqualTo(1500);
      assertThat(amounts)
          .hasSize(4)
          .containsEntry("KWD/" + f.alice().user().id(), -500L)
          .containsEntry("KWD/" + f.bob().user().id(), 500L);
    }
  }

  private void awaitBlockedBy(int blocker) {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select exists(select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
              Boolean.class,
              blocker))) return;
      Thread.yield();
    }
    throw new AssertionError("Expected a real PostgreSQL lock wait");
  }
}
