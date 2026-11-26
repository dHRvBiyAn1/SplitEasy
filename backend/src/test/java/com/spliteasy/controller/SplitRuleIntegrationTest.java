package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** P05: private, versioned saved split rules for the ratio split types. */
@AutoConfigureMockMvc
class SplitRuleIntegrationTest extends AbstractIntegrationTest {
  AuthResponse alice, bob;
  String group;

  void setUp() throws Exception {
    String s = UUID.randomUUID().toString();
    alice = register("ra-" + s + "@example.com", "password123", "Alice");
    bob = register("rb-" + s + "@example.com", "password123", "Bob");
    group =
        objectMapper
            .readTree(
                mockMvc
                    .perform(
                        jsonPost("/api/groups", new CreateGroupRequest("Flat"))
                            .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asText();
    mockMvc.perform(
        jsonPost("/api/groups/" + group + "/members", new AddMemberRequest(bob.user().email()))
            .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())));
  }

  Map<String, Object> rule(String name, String type, long a, long b, Long version) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("splitType", type);
    body.put(
        "inputs",
        List.of(
            Map.of("userId", alice.user().id(), "value", a),
            Map.of("userId", bob.user().id(), "value", b)));
    if (version != null) body.put("expectedVersion", version);
    return body;
  }

  JsonNode call(MockHttpServletRequestBuilder request, AuthResponse who, int status)
      throws Exception {
    MvcResult r =
        mockMvc
            .perform(request.header(HttpHeaders.AUTHORIZATION, bearer(who.accessToken())))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(status);
    String body = r.getResponse().getContentAsString();
    return body.isEmpty() ? null : objectMapper.readTree(body);
  }

  MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b, Object body)
      throws Exception {
    return b.contentType("application/json").content(json(body));
  }

  String base() {
    return "/api/groups/" + group + "/split-rules";
  }

  @Test
  void ownerCreatesListsUpdatesAndDeletesWithVersions() throws Exception {
    setUp();
    var created =
        call(write(post(base()), rule("Rent 60/40", "PERCENTAGE", 6000, 4000, null)), alice, 201);
    assertThat(created.get("version").asLong()).isZero();
    assertThat(created.get("inputs").get(0).get("value").asLong()).isEqualTo(6000);
    String id = created.get("id").asText();
    assertThat(call(get(base()), alice, 200)).hasSize(1);

    var updated =
        call(
            write(put(base() + "/" + id), rule("Rent 70/30", "PERCENTAGE", 7000, 3000, 0L)),
            alice,
            200);
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    assertThat(updated.get("name").asText()).isEqualTo("Rent 70/30");
    // The stale version is refused before anything changes.
    call(write(put(base() + "/" + id), rule("Late", "PERCENTAGE", 5000, 5000, 0L)), alice, 409);
    call(delete(base() + "/" + id).param("expectedVersion", "0"), alice, 409);
    call(delete(base() + "/" + id).param("expectedVersion", "1"), alice, 204);
    assertThat(call(get(base()), alice, 200)).isEmpty();
  }

  @Test
  void equalAndWeightsRulesAreSavedAndDuplicateNamesConflict() throws Exception {
    setUp();
    call(write(post(base()), rule("Even", "EQUAL", 1, 1, null)), alice, 201);
    call(write(post(base()), rule("Heavy", "WEIGHTS", 3, 1, null)), alice, 201);
    call(write(post(base()), rule("even", "WEIGHTS", 3, 1, null)), alice, 409);
  }

  @Test
  void rulesArePrivateAndMemberOnly() throws Exception {
    setUp();
    var created = call(write(post(base()), rule("Mine", "WEIGHTS", 2, 1, null)), alice, 201);
    assertThat(call(get(base()), bob, 200)).isEmpty();
    call(
        write(put(base() + "/" + created.get("id").asText()), rule("Stolen", "WEIGHTS", 1, 1, 0L)),
        bob,
        404);
    call(delete(base() + "/" + created.get("id").asText()).param("expectedVersion", "0"), bob, 404);
    var mallory = register("m-" + UUID.randomUUID() + "@example.com", "password123", "Mallory");
    call(get(base()), mallory, 403);
    call(write(post(base()), rule("Nope", "EQUAL", 1, 1, null)), mallory, 403);
  }

  @Test
  void amountTypesAndBadRatiosAreRejected() throws Exception {
    setUp();
    call(write(post(base()), rule("Exact", "UNEQUAL", 100, 200, null)), alice, 400);
    call(write(post(base()), rule("Adj", "ADJUSTMENTS", 100, -100, null)), alice, 400);
    call(write(post(base()), rule("Short", "PERCENTAGE", 5000, 4000, null)), alice, 400);
    call(write(post(base()), rule("Zero", "WEIGHTS", 0, 1, null)), alice, 400);
    call(write(post(base()), rule("", "EQUAL", 1, 1, null)), alice, 400);
  }

  @Test
  void archivedGroupRefusesWritesButStillReads() throws Exception {
    setUp();
    call(write(post(base()), rule("Keep", "EQUAL", 1, 1, null)), alice, 201);
    call(post("/api/groups/" + group + "/archive"), alice, 200);
    call(write(post(base()), rule("New", "EQUAL", 1, 1, null)), alice, 409);
    assertThat(call(get(base()), alice, 200)).hasSize(1);
  }
}
