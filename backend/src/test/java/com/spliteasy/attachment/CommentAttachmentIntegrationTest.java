package com.spliteasy.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * H02-A: expense comments and PostgreSQL-backed attachments, including the upload trust boundary.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class CommentAttachmentIntegrationTest extends AbstractIntegrationTest {
  private static final int MAX = 5 * 1024 * 1024;
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
  private static final byte[] PDF = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] WEBP =
      "RIFF\u0001\u0002\u0003\u0004WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

  private AuthResponse account(String name) throws Exception {
    return register(UUID.randomUUID() + "@example.test", "password123", name);
  }

  private AbstractMockHttpServletRequestBuilder<?> authed(
      AuthResponse actor, AbstractMockHttpServletRequestBuilder<?> r) {
    return r.header("Authorization", bearer(actor.accessToken()))
        .header("X-Ledger-Version", "2")
        .header("X-Participant-Version", "1");
  }

  private JsonNode send(
      AuthResponse actor, AbstractMockHttpServletRequestBuilder<?> request, int expected)
      throws Exception {
    var result =
        mockMvc.perform(authed(actor, request)).andExpect(status().is(expected)).andReturn();
    String body = result.getResponse().getContentAsString();
    return objectMapper.readTree(body.isEmpty() ? "null" : body);
  }

  private String group(AuthResponse owner, AuthResponse... members) throws Exception {
    String g =
        send(owner, jsonPost("/api/groups", Map.of("name", "Trip")), 201).path("id").asText();
    for (var m : members)
      send(
          owner, jsonPost("/api/groups/" + g + "/members", Map.of("email", m.user().email())), 200);
    return g;
  }

  private String expense(AuthResponse payer, String url, UUID... split) throws Exception {
    return send(
            payer,
            jsonPost(
                url,
                Map.of(
                    "description",
                    "Dinner",
                    "currency",
                    "USD",
                    "amountMinor",
                    1000,
                    "paidByUserId",
                    payer.user().id(),
                    "participantUserIds",
                    List.of(split))),
            201)
        .path("id")
        .asText();
  }

  private AbstractMockHttpServletRequestBuilder<?> upload(
      String url, String name, byte[] bytes, String type) {
    return multipart(url).file(new MockMultipartFile("file", name, type, bytes));
  }

  private static byte[] pad(byte[] head, int size) {
    byte[] out = Arrays.copyOf(head, size);
    return out;
  }

  private String base(String group, String expense) {
    return "/api/groups/" + group + "/expenses/" + expense;
  }

  @Test
  void commentsAreMemberOnlyAuthoredAndSoftDeletedByTheirAuthor() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String g = group(alice, bob);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id(), bob.user().id());
    String url = base(g, e) + "/comments";

    var c = send(bob, jsonPost(url, Map.of("body", "I had the fish")), 201);
    assertThat(c.path("body").asText()).isEqualTo("I had the fish");
    assertThat(c.path("author").path("id").asText()).isEqualTo(bob.user().id().toString());

    assertThat(send(alice, get(url), 200)).hasSize(1);
    send(outsider, get(url), 403);
    send(outsider, jsonPost(url, Map.of("body", "hi")), 403);
    send(outsider, get(base(UUID.randomUUID().toString(), e) + "/comments"), 403);
    send(alice, jsonPost(url, Map.of("body", "  ")), 400);
    send(alice, jsonPost(url, Map.of("body", "x".repeat(2001))), 400);
    send(alice, get(base(g, UUID.randomUUID().toString()) + "/comments"), 404);

    String id = c.path("id").asText();
    send(alice, delete(url + "/" + id), 403);
    send(bob, delete(url + "/" + id), 204);
    send(bob, delete(url + "/" + id), 404);
    assertThat(send(alice, get(url), 200)).isEmpty();
  }

  @Test
  void commentRaisesOneExpensesPreferenceGatedEventForTheOtherMembers() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var carol = account("Carol");
    String g = group(alice, bob, carol);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id(), bob.user().id());
    send(
        carol,
        put("/api/me/notification-preferences")
            .contentType("application/json")
            .content("{\"expenses\":false,\"payments\":true,\"groupChanges\":true}"),
        200);
    long bobBefore = unread(bob);
    long carolBefore = unread(carol);
    long aliceBefore = unread(alice);

    send(alice, jsonPost(base(g, e) + "/comments", Map.of("body", "paid!")), 201);

    assertThat(unread(bob)).isEqualTo(bobBefore + 1);
    assertThat(unread(carol)).isEqualTo(carolBefore);
    assertThat(unread(alice)).isEqualTo(aliceBefore);
    var items = send(bob, get("/api/activity?groupId=" + g), 200).path("items");
    assertThat(items.get(0).path("kind").asText()).isEqualTo("COMMENT_CREATED");
  }

  private long unread(AuthResponse who) throws Exception {
    return send(who, get("/api/notifications/unread-count"), 200).path("count").asLong();
  }

  @Test
  void archivedGroupKeepsReadsButRefusesEveryWrite() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id());
    var c = send(alice, jsonPost(base(g, e) + "/comments", Map.of("body", "a")), 201);
    var a = send(alice, upload(base(g, e) + "/attachments", "a.png", PNG, "image/png"), 201);
    send(alice, jsonPost("/api/groups/" + g + "/archive", Map.of()), 200);

    send(alice, jsonPost(base(g, e) + "/comments", Map.of("body", "b")), 409);
    send(alice, upload(base(g, e) + "/attachments", "b.png", PNG, "image/png"), 409);
    send(alice, delete(base(g, e) + "/comments/" + c.path("id").asText()), 409);
    send(alice, delete(base(g, e) + "/attachments/" + a.path("id").asText()), 409);
    assertThat(send(alice, get(base(g, e) + "/comments"), 200)).hasSize(1);
    assertThat(send(alice, get(base(g, e) + "/attachments"), 200)).hasSize(1);
  }

  @Test
  void uploadStoresTheDetectedTypeAndDownloadIsLockedDown() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String g = group(alice, bob);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id(), bob.user().id());
    String url = base(g, e) + "/attachments";

    // Client claims HTML with a hostile path-like name; the bytes are a JPEG.
    var a = send(bob, upload(url, "../../etc/\"evil\".html", JPEG, "text/html"), 201);
    assertThat(a.path("contentType").asText()).isEqualTo("image/jpeg");
    assertThat(a.path("sizeBytes").asLong()).isEqualTo(JPEG.length);
    assertThat(a.path("fileName").asText()).doesNotContain("/", "\\", "\"").contains("evil");
    assertThat(a.has("content")).isFalse();
    assertThat(a.path("sha256").asText()).hasSize(64);
    assertThat(a.path("uploadedBy").path("id").asText()).isEqualTo(bob.user().id().toString());

    var list = send(alice, get(url), 200);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).has("content")).isFalse();

    String id = a.path("id").asText();
    var download =
        mockMvc
            .perform(authed(alice, get(url + "/" + id)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(download.getContentAsByteArray()).isEqualTo(JPEG);
    assertThat(download.getContentType()).isEqualTo("image/jpeg");
    assertThat(download.getHeader("Content-Disposition")).startsWith("attachment");
    assertThat(download.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(download.getHeader("Cache-Control")).contains("private").contains("no-store");
    send(outsider, get(url + "/" + id), 403);

    send(alice, delete(url + "/" + id), 403);
    send(bob, delete(url + "/" + id), 204);
    send(alice, get(url + "/" + id), 404);
    assertThat(send(alice, get(url), 200)).isEmpty();
  }

  @Test
  void everyAllowedTypeIsAcceptedByItsMagicBytes() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id());
    String url = base(g, e) + "/attachments";
    // The client header is irrelevant: octet-stream still works when the bytes are right.
    assertThat(
            send(alice, upload(url, "a", PNG, "application/octet-stream"), 201)
                .path("contentType")
                .asText())
        .isEqualTo("image/png");
    assertThat(send(alice, upload(url, "b", PDF, "image/png"), 201).path("contentType").asText())
        .isEqualTo("application/pdf");
    assertThat(send(alice, upload(url, "c", WEBP, "image/webp"), 201).path("contentType").asText())
        .isEqualTo("image/webp");
  }

  @Test
  void spoofedEmptyAndUnsupportedUploadsAreRejected() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id());
    String url = base(g, e) + "/attachments";
    byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
    byte[] exe = {'M', 'Z', 0, 0};
    byte[] riffNotWebp =
        "RIFF\u0001\u0002\u0003\u0004WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1);

    send(alice, upload(url, "x.png", html, "image/png"), 400);
    send(alice, upload(url, "x.pdf", exe, "application/pdf"), 400);
    send(alice, upload(url, "x.webp", riffNotWebp, "image/webp"), 400);
    send(alice, upload(url, "x.png", new byte[0], "image/png"), 400);
    send(alice, upload(url, "x.png", Arrays.copyOf(PNG, 4), "image/png"), 400);
    send(
        alice, multipart(url).file(new MockMultipartFile("other", "x.png", "image/png", PNG)), 400);
    assertThat(send(alice, get(url), 200)).isEmpty();
  }

  @Test
  void oversizeIsRejectedAtTheCapAndTheTenAttachmentLimitHolds() throws Exception {
    var alice = account("Alice");
    String g = group(alice);
    String e = expense(alice, "/api/groups/" + g + "/expenses", alice.user().id());
    String url = base(g, e) + "/attachments";

    send(alice, upload(url, "big.png", pad(PNG, MAX + 1), "image/png"), 413);
    assertThat(send(alice, get(url), 200)).isEmpty();
    var exact = send(alice, upload(url, "max.png", pad(PNG, MAX), "image/png"), 201);
    assertThat(exact.path("sizeBytes").asLong()).isEqualTo(MAX);
    for (int i = 1; i < 10; i++) send(alice, upload(url, "s" + i + ".png", PNG, "image/png"), 201);
    send(alice, upload(url, "eleventh.png", PNG, "image/png"), 409);
    send(alice, delete(url + "/" + exact.path("id").asText()), 204);
    send(alice, upload(url, "again.png", PNG, "image/png"), 201);
  }

  @Test
  void personalExpensesMirrorTheRoutesAndHideTheLedgerGroup() throws Exception {
    var alice = account("Alice");
    var bob = account("Bob");
    var outsider = account("Outsider");
    String g = group(alice, bob);
    send(
        alice,
        jsonPost("/api/friends", Map.of("groupId", g, "participantId", bob.user().id())),
        200);
    send(
        bob,
        jsonPost("/api/friends", Map.of("groupId", g, "participantId", alice.user().id())),
        200);
    String root = "/api/friends/" + bob.user().id() + "/expenses";
    var created =
        send(
            alice,
            jsonPost(
                root,
                Map.of(
                    "description",
                    "Taxi",
                    "currency",
                    "USD",
                    "amountMinor",
                    600,
                    "paidByUserId",
                    alice.user().id(),
                    "participantUserIds",
                    List.of(alice.user().id(), bob.user().id()))),
            201);
    String e = created.path("id").asText();
    String ledger = created.path("groupId").asText();

    var c = send(alice, jsonPost(root + "/" + e + "/comments", Map.of("body", "ride home")), 201);
    var a =
        send(alice, upload(root + "/" + e + "/attachments", "r.pdf", PDF, "application/pdf"), 201);
    String bobRoot = "/api/friends/" + alice.user().id() + "/expenses/" + e;
    assertThat(send(bob, get(bobRoot + "/comments"), 200)).hasSize(1);
    assertThat(send(bob, get(bobRoot + "/attachments"), 200)).hasSize(1);
    send(bob, delete(bobRoot + "/comments/" + c.path("id").asText()), 403);
    send(bob, delete(bobRoot + "/attachments/" + a.path("id").asText()), 403);
    send(alice, delete(root + "/" + e + "/comments/" + c.path("id").asText()), 204);
    send(outsider, get("/api/friends/" + bob.user().id() + "/expenses/" + e + "/comments"), 403);
    // The hidden ledger is never addressable as a group, even by its own members.
    send(alice, get(base(ledger, e) + "/comments"), 404);
    send(alice, get(base(ledger, e) + "/attachments"), 404);
  }
}
