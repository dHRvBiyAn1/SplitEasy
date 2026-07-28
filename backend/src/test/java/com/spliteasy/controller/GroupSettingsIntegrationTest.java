package com.spliteasy.controller;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.UpdateGroupRequest;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The group settings page: rename, the per-group simplify-debts flag, removing members /
 * leaving (both gated on a settled balance), and creator-only deletion.
 */
@AutoConfigureMockMvc
class GroupSettingsIntegrationTest extends AbstractIntegrationTest {

    private String createGroup(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/groups", new CreateGroupRequest(name))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String addMember(String token, String groupId, String email) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members",
                        new AddMemberRequest(email))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode m : objectMapper.readTree(result.getResponse().getContentAsString()).get("members")) {
            if (email.equals(m.get("email").asText())) {
                return m.get("id").asText();
            }
        }
        throw new IllegalStateException("member not found in response");
    }

    private String creatorIdOf(String token, String groupId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("createdBy").get("id").asText();
    }

    private void patchGroup(String token, String groupId, UpdateGroupRequest body, int expectedStatus)
            throws Exception {
        mockMvc.perform(patch("/api/groups/" + groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void renamesTheGroupAndLeavesOtherFieldsAlone() throws Exception {
        AuthResponse owner = register("gs-rename@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "Old Name");

        mockMvc.perform(patch("/api/groups/" + groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateGroupRequest("New Name", null, null)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.simplifyDebts").value(true)); // untouched by a name-only patch
    }

    @Test
    void simplifyDebtsDefaultsOnAndCanBeTurnedOff() throws Exception {
        AuthResponse owner = register("gs-simplify@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "Simplify Me");

        mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(jsonPath("$.simplifyDebts").value(true));

        mockMvc.perform(patch("/api/groups/" + groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateGroupRequest(null, null, false)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simplifyDebts").value(false))
                .andExpect(jsonPath("$.name").value("Simplify Me"));
    }

    @Test
    void blankNameIsRejected() throws Exception {
        AuthResponse owner = register("gs-blank@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "Keep Me");

        patchGroup(owner.accessToken(), groupId, new UpdateGroupRequest("   ", null, null), 400);
    }

    @Test
    void nonMemberCannotUpdateTheGroup() throws Exception {
        AuthResponse owner = register("gs-owner-a@example.com", "password123", "Owner");
        AuthResponse outsider = register("gs-outsider@example.com", "password123", "Outsider");
        String groupId = createGroup(owner.accessToken(), "Private");

        patchGroup(outsider.accessToken(), groupId, new UpdateGroupRequest("Hijacked", null, null), 403);
    }

    @Test
    void removesASettledMember() throws Exception {
        AuthResponse owner = register("gs-rm-owner@example.com", "password123", "Owner");
        register("gs-rm-friend@example.com", "password123", "Friend");
        String groupId = createGroup(owner.accessToken(), "Removable");
        String friendId = addMember(owner.accessToken(), groupId, "gs-rm-friend@example.com");

        mockMvc.perform(delete("/api/groups/" + groupId + "/members/" + friendId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(jsonPath("$.members", hasSize(1)));
    }

    @Test
    void cannotRemoveAMemberWithAnOpenBalance() throws Exception {
        AuthResponse owner = register("gs-bal-owner@example.com", "password123", "Owner");
        register("gs-bal-friend@example.com", "password123", "Friend");
        String groupId = createGroup(owner.accessToken(), "Owing");
        String friendId = addMember(owner.accessToken(), groupId, "gs-bal-friend@example.com");
        String ownerId = creatorIdOf(owner.accessToken(), groupId);

        // Owner pays 10.00 split equally: the friend now owes 5.00.
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses",
                        new CreateExpenseRequest("Dinner", 1000L, UUID.fromString(ownerId),
                                List.of(UUID.fromString(ownerId), UUID.fromString(friendId)),
                                null, null, null, null))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/groups/" + groupId + "/members/" + friendId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isConflict());
    }

    @Test
    void memberCanLeaveWhenSettled() throws Exception {
        AuthResponse owner = register("gs-leave-owner@example.com", "password123", "Owner");
        AuthResponse friend = register("gs-leave-friend@example.com", "password123", "Friend");
        String groupId = createGroup(owner.accessToken(), "Leavable");
        String friendId = addMember(owner.accessToken(), groupId, "gs-leave-friend@example.com");

        // Leaving is the same endpoint, targeting yourself.
        mockMvc.perform(delete("/api/groups/" + groupId + "/members/" + friendId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(friend.accessToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void creatorCannotLeaveAndCannotBeRemoved() throws Exception {
        AuthResponse owner = register("gs-creator@example.com", "password123", "Owner");
        AuthResponse friend = register("gs-creator-friend@example.com", "password123", "Friend");
        String groupId = createGroup(owner.accessToken(), "Owned");
        addMember(owner.accessToken(), groupId, "gs-creator-friend@example.com");
        String ownerId = creatorIdOf(owner.accessToken(), groupId);

        mockMvc.perform(delete("/api/groups/" + groupId + "/members/" + ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/groups/" + groupId + "/members/" + ownerId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(friend.accessToken())))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyTheCreatorCanDeleteTheGroup() throws Exception {
        AuthResponse owner = register("gs-del-owner@example.com", "password123", "Owner");
        AuthResponse friend = register("gs-del-friend@example.com", "password123", "Friend");
        String groupId = createGroup(owner.accessToken(), "Doomed");
        addMember(owner.accessToken(), groupId, "gs-del-friend@example.com");

        mockMvc.perform(delete("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(friend.accessToken())))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isNoContent());

        // Gone for everyone — a non-member gets 403 (membership is checked before existence).
        mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingAGroupTakesItsExpensesWithIt() throws Exception {
        AuthResponse owner = register("gs-cascade@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "Cascade");
        String ownerId = creatorIdOf(owner.accessToken(), groupId);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses",
                        new CreateExpenseRequest("Solo lunch", 500L, UUID.fromString(ownerId),
                                List.of(UUID.fromString(ownerId)), null, null, null, null))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated());

        // Succeeds only if the expense/participant rows cascade with the group.
        mockMvc.perform(delete("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isNoContent());
    }
}
