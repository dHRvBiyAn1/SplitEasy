package com.spliteasy.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** H04: recurring schedules driven directly through the runner with a controllable clock. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(RecurrenceIntegrationTest.ClockConfig.class)
class RecurrenceIntegrationTest extends AbstractIntegrationTest {
  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-03-01T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TestConfiguration
  static class ClockConfig {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired RecurrenceRunner runner;
  @Autowired JdbcTemplate jdbc;

  @org.junit.jupiter.api.BeforeEach
  void resetClock() {
    at("2026-03-01T10:00:00Z");
  }

  AuthResponse account(String name) throws Exception {
    return register(UUID.randomUUID() + "@example.test", "password123", name);
  }

  JsonNode send(AuthResponse who, MockHttpServletRequestBuilder request, int expected)
      throws Exception {
    var result =
        mockMvc
            .perform(
                request
                    .header("Authorization", bearer(who.accessToken()))
                    .header("X-Ledger-Version", "2")
                    .header("X-Participant-Version", "1"))
            .andExpect(status().is(expected))
            .andReturn();
    String body = result.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  String group(AuthResponse owner, AuthResponse... members) throws Exception {
    String group =
        send(owner, jsonPost("/api/groups", Map.of("name", "Flat")), 201).path("id").asText();
    for (var m : members)
      send(
          owner,
          jsonPost("/api/groups/" + group + "/members", Map.of("email", m.user().email())),
          200);
    return group;
  }

  Map<String, Object> template(AuthResponse payer, long amount, AuthResponse... split) {
    var body = new HashMap<String, Object>();
    body.put("description", "Rent");
    body.put("currency", "USD");
    body.put("amountMinor", amount);
    body.put("paidByUserId", payer.user().id());
    body.put("splitType", "EQUAL");
    body.put("participantUserIds", java.util.Arrays.stream(split).map(s -> s.user().id()).toList());
    return body;
  }

  Map<String, Object> schedule(
      Map<String, Object> template, String frequency, String anchor, String zone) {
    return new HashMap<>(
        Map.of(
            "template", template, "frequency", frequency, "anchorDate", anchor, "timeZone", zone));
  }

  JsonNode create(AuthResponse who, String g, Map<String, Object> body) throws Exception {
    return send(who, jsonPost("/api/groups/" + g + "/recurring", body), 201);
  }

  void at(String instant) {
    clock.now = Instant.parse(instant);
  }

  List<String> spentOn(AuthResponse who, String g) throws Exception {
    var out = new java.util.ArrayList<String>();
    send(who, get("/api/groups/" + g + "/expenses"), 200)
        .forEach(e -> out.add(e.path("spentOn").asText()));
    java.util.Collections.sort(out);
    return out;
  }

  long occurrences(String schedule, String status) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM recurring_occurrences WHERE schedule_id=?::uuid AND status=?",
        Long.class,
        schedule,
        status);
  }

  @Test
  void concurrentRunnersAndRestartsCreateExactlyOneExpensePerOccurrence() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob);
    var s =
        create(alice, g, schedule(template(alice, 1000, alice, bob), "DAILY", "2026-03-01", "UTC"));
    assertThat(s.path("status").asText()).isEqualTo("ACTIVE");

    var pool = Executors.newFixedThreadPool(4);
    List<Callable<Object>> jobs = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++)
      jobs.add(
          () -> {
            runner.run();
            return null;
          });
    for (var f : pool.invokeAll(jobs)) f.get();
    pool.shutdown();
    assertThat(spentOn(alice, g)).containsExactly("2026-03-01");

    runner.run(); // a restart re-running the same instant adds nothing
    assertThat(spentOn(alice, g)).hasSize(1);

    at("2026-03-03T09:00:00Z");
    runner.run();
    assertThat(spentOn(alice, g)).containsExactly("2026-03-01", "2026-03-02", "2026-03-03");
  }

  @Test
  void occurrenceDatesAreLocalDatesInTheScheduleZone() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    // 12:00Z on 8 Nov is 07:00 in New York, a day after the clocks went back on 1 Nov.
    create(
        alice,
        g,
        schedule(template(alice, 500, alice), "WEEKLY", "2026-10-31", "America/New_York"));
    at("2026-11-08T12:00:00Z");
    runner.run();
    assertThat(spentOn(alice, g)).containsExactly("2026-10-31", "2026-11-07");
  }

  @Test
  void aLongOutageCreatesTheLastThirtyDaysAndRecordsOlderOnesAsSkipped() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    var s = create(alice, g, schedule(template(alice, 100, alice), "DAILY", "2026-01-01", "UTC"));
    at("2026-02-15T00:00:00Z");
    runner.run();
    String id = s.path("id").asText();
    assertThat(occurrences(id, "SKIPPED")).isEqualTo(15); // 1–15 Jan
    assertThat(spentOn(alice, g)).hasSize(31); // 16 Jan–15 Feb
    assertThat(occurrences(id, "CREATED")).isEqualTo(31);
  }

  @Test
  void anInactiveParticipantHaltsTheScheduleForReviewUntilItIsEdited() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    String g = group(alice, bob);
    var s =
        create(alice, g, schedule(template(alice, 1000, alice, bob), "DAILY", "2026-03-01", "UTC"));
    String id = s.path("id").asText();
    send(alice, delete("/api/groups/" + g + "/members/" + bob.user().id()), 204);

    runner.run();
    assertThat(spentOn(alice, g)).isEmpty();
    var halted = send(alice, get("/api/groups/" + g + "/recurring/" + id), 200);
    assertThat(halted.path("status").asText()).isEqualTo("NEEDS_REVIEW");
    assertThat(halted.path("reviewReason").asText()).isNotBlank();
    assertThat(occurrences(id, "FAILED")).isEqualTo(1);

    at("2026-03-02T10:00:00Z");
    runner.run();
    assertThat(spentOn(alice, g)).isEmpty(); // still halted

    var fix = schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "UTC");
    fix.put("expectedVersion", halted.path("version").asLong());
    var fixed =
        send(
            alice,
            put("/api/groups/" + g + "/recurring/" + id)
                .contentType("application/json")
                .content(json(fix)),
            200);
    assertThat(fixed.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(fixed.path("reviewReason").isNull()).isTrue();
    runner.run();
    assertThat(spentOn(alice, g)).containsExactly("2026-03-02");

    // a stale version is refused
    send(
        alice,
        put("/api/groups/" + g + "/recurring/" + id)
            .contentType("application/json")
            .content(json(fix)),
        409);
  }

  @Test
  void editingFutureOccurrencesLeavesPastExpensesAndCancelStopsTheRest() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    var s = create(alice, g, schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "UTC"));
    String id = s.path("id").asText();
    runner.run();

    var edit = schedule(template(alice, 2000, alice), "DAILY", "2026-03-01", "UTC");
    edit.put("expectedVersion", s.path("version").asLong());
    send(
        alice,
        put("/api/groups/" + g + "/recurring/" + id)
            .contentType("application/json")
            .content(json(edit)),
        200);
    at("2026-03-02T10:00:00Z");
    runner.run();
    var amounts = new java.util.ArrayList<Long>();
    send(alice, get("/api/groups/" + g + "/expenses"), 200)
        .forEach(e -> amounts.add(e.path("amountMinor").asLong()));
    assertThat(amounts).containsExactlyInAnyOrder(1000L, 2000L);

    var cancelled = send(alice, post("/api/groups/" + g + "/recurring/" + id + "/cancel"), 200);
    assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
    at("2026-03-05T10:00:00Z");
    runner.run();
    assertThat(spentOn(alice, g)).containsExactly("2026-03-01", "2026-03-02");
  }

  @Test
  void schedulesAreMemberOnly() throws Exception {
    var alice = account("Alice");
    var eve = account("Eve");
    String g = group(alice);
    var s = create(alice, g, schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "UTC"));
    send(eve, get("/api/groups/" + g + "/recurring"), 403);
    send(eve, get("/api/groups/" + g + "/recurring/" + s.path("id").asText()), 403);
    send(alice, get("/api/groups/" + g + "/recurring"), 200);
    var bad = schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "Mars/Base");
    send(alice, jsonPost("/api/groups/" + g + "/recurring", bad), 400);
  }

  @Test
  void aReminderGoesOnlyFromACreditorToTheDebtorOnceADay() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String g = group(alice, bob, carol);
    send(alice, jsonPost("/api/groups/" + g + "/expenses", template(alice, 3000, alice, bob)), 201);
    String url = "/api/groups/" + g + "/reminders";
    var body = Map.of("debtorId", bob.user().id(), "currency", "USD");

    send(carol, jsonPost(url, body), 409); // owed nothing by Bob
    send(bob, jsonPost(url, Map.of("debtorId", alice.user().id(), "currency", "USD")), 409);
    send(alice, jsonPost(url, Map.of("debtorId", bob.user().id(), "currency", "EUR")), 409);
    long bobBefore = unread(bob);
    long carolBefore = unread(carol);
    send(alice, jsonPost(url, body), 201);
    assertThat(unread(bob)).isEqualTo(bobBefore + 1);
    assertThat(unread(carol)).isEqualTo(carolBefore);
    send(alice, jsonPost(url, body), 409); // dedup inside 24 hours

    jdbc.update(
        "UPDATE activity_events SET occurred_at = now() - interval '25 hours' WHERE kind='BALANCE_REMINDER'");
    jdbc.update(
        "INSERT INTO notification_preferences(user_id,payments) VALUES (?::uuid,false)",
        bob.user().id());
    send(alice, jsonPost(url, body), 201);
    assertThat(unread(bob))
        .isEqualTo(bobBefore + 1); // switched off: event recorded, no new notification
    send(
        carol,
        jsonPost(
            "/api/groups/" + g + "/reminders",
            Map.of("debtorId", UUID.randomUUID(), "currency", "USD")),
        409);
  }

  @Test
  void aReplayedCreateReturnsTheOriginalScheduleAndAChangedOneIsRefused() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    var body = schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "UTC");
    body.put("operationId", UUID.randomUUID().toString());
    var first = create(alice, g, body);
    var replay = create(alice, g, body);
    assertThat(replay).isEqualTo(first);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recurring_schedules WHERE group_id=?::uuid", Long.class, g))
        .isEqualTo(1);

    var changed = new HashMap<>(body);
    changed.put("template", template(alice, 2000, alice));
    send(alice, jsonPost("/api/groups/" + g + "/recurring", changed), 409);
    // an unkeyed create is always a new schedule
    body.remove("operationId");
    create(alice, g, body);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recurring_schedules WHERE group_id=?::uuid", Long.class, g))
        .isEqualTo(2);
  }

  @Test
  void theTemplateMustBeValidForTheGroupAtCreateAndEdit() throws Exception {
    var alice = account("Alice");
    var eve = account("Eve"); // not in the group
    String g = group(alice);
    String url = "/api/groups/" + g + "/recurring";
    send(
        alice,
        jsonPost(url, schedule(template(eve, 1000, alice), "DAILY", "2026-03-01", "UTC")),
        400);
    send(
        alice,
        jsonPost(url, schedule(template(alice, 1000, alice, eve), "DAILY", "2026-03-01", "UTC")),
        400);
    var unequal = template(alice, 1000, alice);
    unequal.put("splitType", "UNEQUAL");
    unequal.put("splits", List.of(Map.of("userId", alice.user().id(), "value", 400)));
    send(alice, jsonPost(url, schedule(unequal, "DAILY", "2026-03-01", "UTC")), 400);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM recurring_schedules WHERE group_id=?::uuid", Long.class, g))
        .isZero();

    var ok = create(alice, g, schedule(template(alice, 1000, alice), "DAILY", "2026-03-01", "UTC"));
    var bad = schedule(template(eve, 1000, alice), "DAILY", "2026-03-01", "UTC");
    bad.put("expectedVersion", ok.path("version").asLong());
    send(
        alice,
        put(url + "/" + ok.path("id").asText()).contentType("application/json").content(json(bad)),
        400);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM expenses WHERE group_id=?::uuid", Long.class, g))
        .isZero(); // validation never leaves an expense behind
  }

  long unread(AuthResponse who) throws Exception {
    return send(who, get("/api/notifications/unread-count"), 200).path("count").asLong();
  }
}
