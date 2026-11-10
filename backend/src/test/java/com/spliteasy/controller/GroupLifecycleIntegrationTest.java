package com.spliteasy.controller;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.RestoreGroupRequest;
import com.spliteasy.dto.group.UpdateGroupRequest;
import com.spliteasy.dto.payment.RecordPaymentRequest;
import com.spliteasy.entity.GroupType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * G03 group lifecycle: archive stops new financial entries while preserving reads, and deletion is
 * a recoverable creator-only state gated on a settled group rather than a destructive cascade.
 */
@AutoConfigureMockMvc
class GroupLifecycleIntegrationTest extends AbstractIntegrationTest {

  private String createGroup(String token, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest(name))
                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String addMember(String token, String groupId, String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                jsonPost("/api/groups/" + groupId + "/members", new AddMemberRequest(email))
                    .header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
    for (JsonNode m :
        objectMapper.readTree(result.getResponse().getContentAsString()).get("members")) {
      if (email.equals(m.get("email").asText())) {
        return m.get("id").asText();
      }
    }
    throw new IllegalStateException("member not found in response");
  }

  private String selfId(String token, String groupId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("createdBy")
        .get("id")
        .asText();
  }

  private void archive(String token, String groupId, int expected) throws Exception {
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/archive", null)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().is(expected));
  }

  private void unarchive(String token, String groupId, int expected) throws Exception {
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/unarchive", null)
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().is(expected));
  }

  private void addExpense(String token, String groupId, String payerId, long minor, int expected)
      throws Exception {
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + groupId + "/expenses",
                    new CreateExpenseRequest("Entry", minor, UUID.fromString(payerId), null))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().is(expected));
  }

  private long lifecycleVersion(String token, String groupId) throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("lifecycleVersion")
        .asLong();
  }

  @Test
  void archiveStopsNewEntriesButKeepsReadsAndTotals() throws Exception {
    AuthResponse owner = register("g03-archive@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Trip");
    String ownerId = selfId(token, groupId);
    addExpense(token, groupId, ownerId, 5000L, 201);

    archive(token, groupId, 200);

    mockMvc
        .perform(get("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.archivedAt").exists())
        .andExpect(jsonPath("$.name").value("Trip"));
    mockMvc
        .perform(
            get("/api/groups/" + groupId + "/expenses")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].amountMinor").value(5000));
    mockMvc
        .perform(
            get("/api/groups/" + groupId + "/balances")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());

    addExpense(token, groupId, ownerId, 1000L, 409);

    unarchive(token, groupId, 200);
    addExpense(token, groupId, ownerId, 1000L, 201);
    mockMvc
        .perform(get("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(jsonPath("$.archivedAt").value((Object) null));
  }

  @Test
  void archivedGroupRefusesMemberAndPaymentWritesButAllowsSettingsEdits() throws Exception {
    AuthResponse owner = register("g03-arch-writes@example.com", "password123", "Owner");
    register("g03-arch-friend@example.com", "password123", "Friend");
    register("g03-arch-later@example.com", "password123", "Later");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Flat");
    String friendId = addMember(token, groupId, "g03-arch-friend@example.com");
    String ownerId = selfId(token, groupId);

    archive(token, groupId, 200);

    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + groupId + "/members",
                    new AddMemberRequest("g03-arch-later@example.com"))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + groupId + "/payments",
                    new RecordPaymentRequest(
                        UUID.fromString(friendId), UUID.fromString(ownerId), 100L, null))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            patch("/api/groups/" + groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UpdateGroupRequest("Flat Archive", GroupType.HOME, null)))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Flat Archive"))
        .andExpect(jsonPath("$.type").value("HOME"));
  }

  @Test
  void archiveIsMemberLevelAndHiddenFromNonMembers() throws Exception {
    AuthResponse owner = register("g03-arch-auth@example.com", "password123", "Owner");
    AuthResponse member = register("g03-arch-member@example.com", "password123", "Member");
    AuthResponse outsider = register("g03-arch-outsider@example.com", "password123", "Outsider");
    String groupId = createGroup(owner.accessToken(), "Shared");
    addMember(owner.accessToken(), groupId, "g03-arch-member@example.com");

    archive(outsider.accessToken(), groupId, 403);
    archive(outsider.accessToken(), UUID.randomUUID().toString(), 403);
    archive(member.accessToken(), groupId, 200);
    unarchive(member.accessToken(), groupId, 200);
  }

  @Test
  void unsettledDeleteConflictsAndSettledDeleteIsRecoverable() throws Exception {
    AuthResponse owner = register("g03-del-owner@example.com", "password123", "Owner");
    register("g03-del-friend@example.com", "password123", "Friend");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Settle First");
    String friendId = addMember(token, groupId, "g03-del-friend@example.com");
    String ownerId = selfId(token, groupId);
    addExpense(token, groupId, ownerId, 1000L, 201);

    mockMvc
        .perform(delete("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isConflict());

    mockMvc
        .perform(
            jsonPost(
                    "/api/groups/" + groupId + "/payments",
                    new RecordPaymentRequest(
                        UUID.fromString(friendId), UUID.fromString(ownerId), 500L, null))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isCreated());

    long version = lifecycleVersion(token, groupId);
    mockMvc
        .perform(delete("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
    mockMvc
        .perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.groups", hasSize(0)));

    mockMvc
        .perform(get("/api/groups/deleted").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(groupId));

    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isConflict()); // stale: deletion advanced the lifecycle version

    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version + 1))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.members", hasSize(2)));

    mockMvc
        .perform(
            get("/api/groups/" + groupId + "/expenses")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].amountMinor").value(1000));
    mockMvc
        .perform(
            get("/api/groups/" + groupId + "/payments")
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void onlyTheCreatorDeletesOrRestores() throws Exception {
    AuthResponse owner = register("g03-cr-owner@example.com", "password123", "Owner");
    AuthResponse member = register("g03-cr-member@example.com", "password123", "Member");
    AuthResponse outsider = register("g03-cr-outsider@example.com", "password123", "Outsider");
    String groupId = createGroup(owner.accessToken(), "Creator Only");
    addMember(owner.accessToken(), groupId, "g03-cr-member@example.com");

    mockMvc
        .perform(
            delete("/api/groups/" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearer(member.accessToken())))
        .andExpect(status().isForbidden());

    long version = lifecycleVersion(owner.accessToken(), groupId);
    mockMvc
        .perform(
            delete("/api/groups/" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version + 1))
                .header(HttpHeaders.AUTHORIZATION, bearer(member.accessToken())))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/groups/deleted")
                .header(HttpHeaders.AUTHORIZATION, bearer(member.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version + 1))
                .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
        .andExpect(status().isForbidden());
  }

  @Test
  void deletedGroupRefusesEveryWriteAndRepeatedDeletes() throws Exception {
    AuthResponse owner = register("g03-gone@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Gone");
    String ownerId = selfId(token, groupId);

    mockMvc
        .perform(delete("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNoContent());

    addExpense(token, groupId, ownerId, 100L, 404);
    mockMvc
        .perform(
            patch("/api/groups/" + groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(new UpdateGroupRequest("Resurrect", null, null)))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNotFound());
    archive(token, groupId, 404);
    mockMvc
        .perform(delete("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNotFound());
  }

  @Test
  void archivedGroupsStillListWithTheirArchivedState() throws Exception {
    AuthResponse owner = register("g03-list@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String archivedId = createGroup(token, "Archived One");
    createGroup(token, "Active One");
    archive(token, archivedId, 200);

    mockMvc
        .perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        // Jayway yields one entry per matched group, carrying that field's actual value.
        .andExpect(jsonPath("$[?(@.name == 'Archived One')].archivedAt", hasSize(1)))
        .andExpect(
            jsonPath(
                "$[?(@.name == 'Archived One')].archivedAt", everyItem(notNullValue(Object.class))))
        .andExpect(jsonPath("$[?(@.name == 'Active One')].archivedAt", hasSize(1)))
        .andExpect(
            jsonPath(
                "$[?(@.name == 'Active One')].archivedAt", everyItem(nullValue(Object.class))));
  }

  @Test
  void restoreRequiresTheExpectedVersionAndCannotRunTwice() throws Exception {
    AuthResponse owner = register("g03-restore-twice@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Once");
    long version = lifecycleVersion(token, groupId);

    mockMvc
        .perform(delete("/api/groups/" + groupId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(null))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version + 1))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPost("/api/groups/" + groupId + "/restore", new RestoreGroupRequest(version + 2))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(status().isConflict());
    mockMvc
        .perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer(token)))
        .andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void leavingAnArchivedGroupStaysAvailableForSettledMembers() throws Exception {
    AuthResponse owner = register("g03-leave-owner@example.com", "password123", "Owner");
    AuthResponse member = register("g03-leave-member@example.com", "password123", "Member");
    String groupId = createGroup(owner.accessToken(), "Archived Leave");
    String memberId = addMember(owner.accessToken(), groupId, "g03-leave-member@example.com");
    archive(owner.accessToken(), groupId, 200);

    mockMvc
        .perform(
            delete("/api/groups/" + groupId + "/members/" + memberId)
                .header(HttpHeaders.AUTHORIZATION, bearer(member.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            get("/api/groups/" + groupId)
                .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
        .andExpect(jsonPath("$.members", hasSize(1)));
  }

  @Test
  void archivingTwiceOrUnarchivingAnActiveGroupConflicts() throws Exception {
    AuthResponse owner = register("g03-idempotent@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String groupId = createGroup(token, "States");

    unarchive(token, groupId, 409);
    archive(token, groupId, 200);
    archive(token, groupId, 409);
    unarchive(token, groupId, 200);
  }

  @Test
  void everyGroupTypeCanBeSelectedFromSettings() throws Exception {
    AuthResponse owner = register("g03-types@example.com", "password123", "Owner");
    String token = owner.accessToken();
    String groupId = createGroup(token, "Typed");

    for (GroupType type : GroupType.values()) {
      mockMvc
          .perform(
              patch("/api/groups/" + groupId)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(new UpdateGroupRequest(null, type, null)))
                  .header(HttpHeaders.AUTHORIZATION, bearer(token)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.type").value(type.name()));
    }
  }
}
