package com.spliteasy.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.CreateGroupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class GroupFlowIntegrationTest extends AbstractIntegrationTest {

    private String createGroupReturningId(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/groups", new CreateGroupRequest(name))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    @Test
    void createGroupMakesCreatorTheOnlyMember() throws Exception {
        AuthResponse owner = register("owner1@example.com", "password123", "Owner One");
        mockMvc.perform(jsonPost("/api/groups", new CreateGroupRequest("Trip to Goa"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trip to Goa"))
                .andExpect(jsonPath("$.createdBy.email").value("owner1@example.com"))
                .andExpect(jsonPath("$.members", hasSize(1)))
                .andExpect(jsonPath("$.members[0].email").value("owner1@example.com"));
    }

    @Test
    void addMemberByEmailAddsThemToTheGroup() throws Exception {
        AuthResponse owner = register("owner2@example.com", "password123", "Owner Two");
        register("friend@example.com", "password123", "Friend");
        String groupId = createGroupReturningId(owner.accessToken(), "Flatmates");

        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members",
                        new AddMemberRequest("friend@example.com"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(2)));
    }

    @Test
    void addMemberWithUnknownEmailReturnsNotFound() throws Exception {
        AuthResponse owner = register("owner3@example.com", "password123", "Owner Three");
        String groupId = createGroupReturningId(owner.accessToken(), "Ghosts");

        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members",
                        new AddMemberRequest("ghost@example.com"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void addingSameMemberTwiceReturnsConflict() throws Exception {
        AuthResponse owner = register("owner4@example.com", "password123", "Owner Four");
        register("twice@example.com", "password123", "Twice");
        String groupId = createGroupReturningId(owner.accessToken(), "Dupe Members");
        AddMemberRequest body = new AddMemberRequest("twice@example.com");

        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isConflict());
    }

    @Test
    void nonMemberCannotViewGroup() throws Exception {
        AuthResponse owner = register("owner5@example.com", "password123", "Owner Five");
        AuthResponse outsider = register("outsider@example.com", "password123", "Outsider");
        String groupId = createGroupReturningId(owner.accessToken(), "Private");

        mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMemberCannotAddMembers() throws Exception {
        AuthResponse owner = register("owner6@example.com", "password123", "Owner Six");
        AuthResponse outsider = register("outsider2@example.com", "password123", "Outsider Two");
        register("victim@example.com", "password123", "Victim");
        String groupId = createGroupReturningId(owner.accessToken(), "Locked");

        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members",
                        new AddMemberRequest("victim@example.com"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMyGroupsReturnsOnlyGroupsImIn() throws Exception {
        AuthResponse me = register("lister@example.com", "password123", "Lister");
        AuthResponse other = register("other@example.com", "password123", "Other");
        createGroupReturningId(me.accessToken(), "Mine A");
        createGroupReturningId(me.accessToken(), "Mine B");
        createGroupReturningId(other.accessToken(), "Not Mine");

        mockMvc.perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer(me.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].memberCount").value(1));
    }

    @Test
    void getGroupDetailsReturnsMembers() throws Exception {
        AuthResponse owner = register("owner7@example.com", "password123", "Owner Seven");
        register("member2@example.com", "password123", "Member Two");
        String groupId = createGroupReturningId(owner.accessToken(), "Detailed");
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members",
                        new AddMemberRequest("member2@example.com"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId))
                .andExpect(jsonPath("$.members", hasSize(2)));
    }
}
