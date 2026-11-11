package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** B01: payment detail, correction, recoverable delete/restore and attributable history. */
@AutoConfigureMockMvc
class PaymentCorrectionIntegrationTest extends AbstractIntegrationTest {

  record Fixture(AuthResponse alice, AuthResponse bob, AuthResponse carol, String group) {}

  Fixture fixture() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var a = register("a-" + suffix + "@example.com", "password123", "Alice");
    var b = register("b-" + suffix + "@example.com", "password123", "Bob");
    var c = register("c-" + suffix + "@example.com", "password123", "Carol");
    String g = createGroup(a.accessToken(), "Corrections");
    addMember(a.accessToken(), g, b.user().email());
    addMember(a.accessToken(), g, c.user().email());
    return new Fixture(a, b, c, g);
  }

  String createGroup(String token, String name) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest(name))
                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
  }

  void addMember(String token, String groupId, String email) throws Exception {
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/members", new AddMemberRequest(email))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
  }

  JsonNode recordPayment(Fixture f, String token, Map<String, Object> body) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                jsonPost("/api/groups/" + f.group() + "/payments", body)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString());
  }

  Map<String, Object> payment(Fixture f, long cents) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payerUserId", f.bob().user().id());
    body.put("payeeUserId", f.alice().user().id());
    body.put("amountCents", cents);
    return body;
  }

  JsonNode read(Fixture f, String token, String path) throws Exception {
    MvcResult r =
        mockMvc
            .perform(
                get("/api/groups/" + f.group() + path)
                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper.readTree(r.getResponse().getContentAsString());
  }

  /** Net balance of one participant in minor units, for the single-currency fixtures. */
  long net(Fixture f, String token, String participantId) throws Exception {
    for (JsonNode b : read(f, token, "/balances").get("balances")) {
      if (b.get("user").get("id").asText().equals(participantId)) return b.get("netMinor").asLong();
    }
    return 0L;
  }

  MvcResult correct(Fixture f, String token, String paymentId, Map<String, Object> body)
      throws Exception {
    return mockMvc
        .perform(
            put("/api/groups/" + f.group() + "/payments/" + paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andReturn();
  }

  MvcResult remove(Fixture f, String token, String paymentId, Long expectedVersion)
      throws Exception {
    return mockMvc
        .perform(
            delete("/api/groups/" + f.group() + "/payments/" + paymentId)
                .param("expectedVersion", String.valueOf(expectedVersion))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andReturn();
  }

  @Test
  void recordedPaymentCarriesFinancialDateMethodNoteRecorderAndVersion() throws Exception {
    var f = fixture();
    Map<String, Object> body = new HashMap<>(payment(f, 2500));
    body.put("paidOn", "2026-03-04");
    body.put("method", "BANK_TRANSFER");
    body.put("note", "Split the deposit back");

    var created = recordPayment(f, f.bob().accessToken(), body);

    assertThat(created.get("paidOn").asText()).isEqualTo("2026-03-04");
    assertThat(created.get("method").asText()).isEqualTo("BANK_TRANSFER");
    assertThat(created.get("note").asText()).isEqualTo("Split the deposit back");
    assertThat(created.get("version").asLong()).isZero();
    assertThat(created.get("recordedBy").get("id").asText())
        .isEqualTo(f.bob().user().id().toString());

    // The payee reads back exactly what the recorder received: a settlement has no viewer-scoped
    // field, so the whole document must match, recordedBy included. This is how a payee sees a
    // correction, so keep the cross-account read rather than re-reading as Bob.
    var detail = read(f, f.alice().accessToken(), "/payments/" + created.get("id").asText());
    assertThat(detail.get("recordedBy").get("id").asText())
        .isEqualTo(f.bob().user().id().toString());
    assertThat(detail).isEqualTo(created);
    // The receipt is storage-faithful: PostgreSQL keeps microseconds and rounds, so a create
    // response carrying nanoseconds would disagree with the row it describes.
    assertThat(java.time.Instant.parse(created.get("createdAt").asText()).getNano() % 1000)
        .isZero();
  }

  Map<String, Object> correction(Fixture f, String payerId, long minor, long expectedVersion) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("payerUserId", payerId);
    body.put("payeeUserId", f.alice().user().id());
    body.put("amountMinor", minor);
    body.put("paidOn", "2026-03-05");
    body.put("method", "CASH");
    body.put("note", "Corrected");
    body.put("expectedVersion", expectedVersion);
    return body;
  }

  @Test
  void correctingTheWrongPayerMovesBalancesOnceAndRecordsOneRevision() throws Exception {
    var f = fixture();
    // Bob settles 40.00 with Alice, but it was really Carol who paid her.
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 4000));
    String id = created.get("id").asText();

    var corrected =
        correct(
            f,
            f.alice().accessToken(),
            id,
            correction(f, f.carol().user().id().toString(), 4000, 0));

    assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
    var after = objectMapper.readTree(corrected.getResponse().getContentAsString());
    assertThat(after.get("payer").get("id").asText()).isEqualTo(f.carol().user().id().toString());
    assertThat(after.get("version").asLong()).isEqualTo(1);
    // Bob is back to square; Carol now carries the settlement she really made.
    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isZero();
    assertThat(net(f, f.alice().accessToken(), f.carol().user().id().toString())).isEqualTo(4000);
    assertThat(net(f, f.alice().accessToken(), f.alice().user().id().toString())).isEqualTo(-4000);

    var revisions = read(f, f.alice().accessToken(), "/payments/" + id + "/history");
    assertThat(revisions.size()).isEqualTo(2);
    var correctedRevision = revisions.get(1);
    assertThat(correctedRevision.get("action").asText()).isEqualTo("CORRECTED");
    assertThat(correctedRevision.get("actor").get("id").asText())
        .isEqualTo(f.alice().user().id().toString());
    assertThat(correctedRevision.get("before").get("payer").get("id").asText())
        .isEqualTo(f.bob().user().id().toString());
    assertThat(correctedRevision.get("after").get("payer").get("id").asText())
        .isEqualTo(f.carol().user().id().toString());
  }

  @Test
  void correctingTheAmountMovesBalancesOnce() throws Exception {
    var f = fixture();
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 5000));
    String id = created.get("id").asText();

    correct(f, f.alice().accessToken(), id, correction(f, f.bob().user().id().toString(), 3000, 0));

    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isEqualTo(3000);
    assertThat(net(f, f.alice().accessToken(), f.alice().user().id().toString())).isEqualTo(-3000);
  }

  @Test
  void keyedRetryAfterACorrectionReplaysTheOriginalReceiptWithoutRecordingAgain() throws Exception {
    var f = fixture();
    Map<String, Object> body = new HashMap<>(payment(f, 5000));
    body.put("operationId", UUID.randomUUID());
    var created = recordPayment(f, f.alice().accessToken(), body);
    String id = created.get("id").asText();
    correct(f, f.alice().accessToken(), id, correction(f, f.bob().user().id().toString(), 3000, 0));

    // The original response was lost; the client retries the identical keyed create.
    var replayed = recordPayment(f, f.alice().accessToken(), body);

    assertThat(replayed).isEqualTo(created);
    assertThat(read(f, f.alice().accessToken(), "/payments").size()).isEqualTo(1);
    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isEqualTo(3000);
  }

  @Test
  void staleCorrectionIsConflictAndLeavesTheLedgerUnchanged() throws Exception {
    var f = fixture();
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 5000));
    String id = created.get("id").asText();
    correct(f, f.alice().accessToken(), id, correction(f, f.bob().user().id().toString(), 3000, 0));

    // A second editor still holds version 0 and tries to apply its own amount.
    var stale =
        correct(
            f, f.bob().accessToken(), id, correction(f, f.bob().user().id().toString(), 9900, 0));

    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isEqualTo(3000);
    assertThat(read(f, f.alice().accessToken(), "/payments/" + id + "/history").size())
        .isEqualTo(2);
  }

  @Test
  void correctionKeepsTheOriginalCurrency() throws Exception {
    var f = fixture();
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 5000));
    Map<String, Object> body = correction(f, f.bob().user().id().toString(), 5000, 0);
    body.put("currency", "EUR");

    var response = correct(f, f.alice().accessToken(), created.get("id").asText(), body);

    assertThat(response.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void deleteRemovesTheSettlementAndRestorePutsItBackOnce() throws Exception {
    var f = fixture();
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 2000));
    String id = created.get("id").asText();

    assertThat(remove(f, f.alice().accessToken(), id, 0L).getResponse().getStatus()).isEqualTo(204);

    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isZero();
    assertThat(read(f, f.alice().accessToken(), "/payments").size()).isZero();
    var deleted = read(f, f.alice().accessToken(), "/payments/deleted");
    assertThat(deleted.size()).isEqualTo(1);
    assertThat(deleted.get(0).get("payment").get("id").asText()).isEqualTo(id);

    var restored =
        mockMvc
            .perform(
                jsonPost(
                        "/api/groups/" + f.group() + "/payments/" + id + "/restore",
                        Map.of("expectedVersion", 1))
                    .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken())))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(
            objectMapper
                .readTree(restored.getResponse().getContentAsString())
                .get("version")
                .asLong())
        .isEqualTo(2);
    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isEqualTo(2000);
    assertThat(read(f, f.alice().accessToken(), "/payments").size()).isEqualTo(1);
    var revisions = read(f, f.alice().accessToken(), "/payments/" + id + "/history");
    assertThat(revisions.size()).isEqualTo(3);
    assertThat(revisions.get(1).get("action").asText()).isEqualTo("DELETED");
    assertThat(revisions.get(2).get("action").asText()).isEqualTo("RESTORED");
  }

  @Test
  void staleDeleteDoesNotReverseASettlementTwice() throws Exception {
    var f = fixture();
    var created = recordPayment(f, f.alice().accessToken(), payment(f, 2000));
    String id = created.get("id").asText();
    remove(f, f.alice().accessToken(), id, 0L);
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/payments/" + id + "/restore",
                    Map.of("expectedVersion", 1))
                .header(HttpHeaders.AUTHORIZATION, bearer(f.alice().accessToken())))
        .andExpect(status().isOk());

    // A retried delete from the stale view must not reverse the restored settlement again.
    assertThat(remove(f, f.bob().accessToken(), id, 0L).getResponse().getStatus()).isEqualTo(409);
    assertThat(net(f, f.alice().accessToken(), f.bob().user().id().toString())).isEqualTo(2000);
  }

  @Test
  void nonMemberGetsForbiddenBeforeNotFoundOnEveryNewRoute() throws Exception {
    var f = fixture();
    String outsiderSuffix = UUID.randomUUID().toString();
    var outsider =
        register("out-" + outsiderSuffix + "@example.com", "password123", "Outsider").accessToken();
    String unknown = UUID.randomUUID().toString();

    mockMvc
        .perform(
            get("/api/groups/" + f.group() + "/payments/" + unknown)
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/groups/" + f.group() + "/payments/" + unknown + "/history")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/groups/" + f.group() + "/payments/deleted")
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
    assertThat(
            correct(f, outsider, unknown, correction(f, f.bob().user().id().toString(), 100, 0))
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(remove(f, outsider, unknown, 0L).getResponse().getStatus()).isEqualTo(403);
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + f.group() + "/payments/" + unknown + "/restore",
                    Map.of("expectedVersion", 0))
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
        .andExpect(status().isForbidden());
  }
}
