package com.spliteasy.controller;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.entity.SplitType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class EditDeleteExpenseIntegrationTest extends AbstractIntegrationTest {

    private String createGroup(String token, String name) throws Exception {
        MvcResult r = mockMvc.perform(jsonPost("/api/groups", new CreateGroupRequest(name))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private void addMember(String token, String groupId, String email) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members", new AddMemberRequest(email))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private String createExpense(String token, String groupId, CreateExpenseRequest body) throws Exception {
        MvcResult r = mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    private Map<String, Long> balances(String token, String groupId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/groups/" + groupId + "/balances")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, Long> byEmail = new HashMap<>();
        for (JsonNode b : objectMapper.readTree(r.getResponse().getContentAsString()).get("balances")) {
            byEmail.put(b.get("user").get("email").asText(), b.get("netCents").asLong());
        }
        return byEmail;
    }

    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void editingAmountRecalculatesSharesAndBalances() throws Exception {
        AuthResponse alice = register("ed-a1@example.com", "password123", "Alice");
        register("ed-b1@example.com", "password123", "Bob");
        register("ed-c1@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Edit");
        addMember(alice.accessToken(), g, "ed-b1@example.com");
        addMember(alice.accessToken(), g, "ed-c1@example.com");

        // Equal $9.00 across 3 → 300 each. Alice +600.
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Dinner", 900L, alice.user().id(), null));
        assertThat(balances(alice.accessToken(), g).get("ed-a1@example.com")).isEqualTo(600);

        // Edit up to $12.00 → 400 each. Alice now +800. Shares recalculated, not stale.
        MvcResult r = mockMvc.perform(put("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Dinner", 1200L, alice.user().id(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents").value(1200))
                .andReturn();
        long total = 0;
        for (JsonNode p : objectMapper.readTree(r.getResponse().getContentAsString()).get("participants")) {
            assertThat(p.get("shareCents").asLong()).isEqualTo(400);
            total += p.get("shareCents").asLong();
        }
        assertThat(total).isEqualTo(1200);

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("ed-a1@example.com")).isEqualTo(800);
        assertThat(bal.get("ed-b1@example.com")).isEqualTo(-400);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void editingSplitTypeEqualToPercentageRecalculates() throws Exception {
        AuthResponse alice = register("ed-a2@example.com", "password123", "Alice");
        AuthResponse bob = register("ed-b2@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Edit2");
        addMember(alice.accessToken(), g, "ed-b2@example.com");

        // Equal $10.00 between 2 → 500/500.
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Rent", 1000L, alice.user().id(), null));

        // Change to PERCENTAGE 25/75 → Alice 250, Bob 750.
        MvcResult r = mockMvc.perform(put("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Rent", 1000L, alice.user().id(), null,
                                SplitType.PERCENTAGE,
                                List.of(new SplitInput(alice.user().id(), 2500L),
                                        new SplitInput(bob.user().id(), 7500L))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.splitType").value("PERCENTAGE"))
                .andReturn();
        long total = 0;
        for (JsonNode p : objectMapper.readTree(r.getResponse().getContentAsString()).get("participants")) {
            total += p.get("shareCents").asLong();
            if (p.get("user").get("email").asText().equals("ed-b2@example.com")) {
                assertThat(p.get("shareCents").asLong()).isEqualTo(750);
            }
        }
        assertThat(total).isEqualTo(1000);
    }

    @Test
    void editWithInvalidSplitIsRejected() throws Exception {
        AuthResponse alice = register("ed-a3@example.com", "password123", "Alice");
        AuthResponse bob = register("ed-b3@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Edit3");
        addMember(alice.accessToken(), g, "ed-b3@example.com");
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Rent", 1000L, alice.user().id(), null));

        // UNEQUAL 600 + 300 = 900 != 1000 → 400.
        mockMvc.perform(put("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Rent", 1000L, alice.user().id(), null,
                                SplitType.UNEQUAL,
                                List.of(new SplitInput(alice.user().id(), 600L),
                                        new SplitInput(bob.user().id(), 300L))))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingRemovesExpenseAndItsShares() throws Exception {
        AuthResponse alice = register("ed-a4@example.com", "password123", "Alice");
        register("ed-b4@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Del");
        addMember(alice.accessToken(), g, "ed-b4@example.com");
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Gone", 1000L, alice.user().id(), null));

        // Balance non-zero before delete.
        assertThat(balances(alice.accessToken(), g).get("ed-a4@example.com")).isEqualTo(500);

        mockMvc.perform(delete("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isNoContent());

        // Gone from the list, gone from the balance calc (back to zero for everyone).
        mockMvc.perform(get("/api/groups/" + g + "/expenses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isNotFound());
        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.values()).allMatch(v -> v == 0L);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void nonMemberCannotEditOrDelete() throws Exception {
        AuthResponse alice = register("ed-a5@example.com", "password123", "Alice");
        AuthResponse outsider = register("ed-out5@example.com", "password123", "Outsider");
        String g = createGroup(alice.accessToken(), "Locked");
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Mine", 1000L, alice.user().id(), null));

        mockMvc.perform(put("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Hacked", 500L, alice.user().id(), null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void editingAnExpenseFromAnotherGroupIs404() throws Exception {
        AuthResponse alice = register("ed-a6@example.com", "password123", "Alice");
        String groupA = createGroup(alice.accessToken(), "A");
        String groupB = createGroup(alice.accessToken(), "B");
        String id = createExpense(alice.accessToken(), groupA,
                new CreateExpenseRequest("A-only", 300L, alice.user().id(), null));

        // Member of both groups, but the expense lives in A — editing it under B is 404.
        mockMvc.perform(put("/api/groups/" + groupB + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Nope", 300L, alice.user().id(), null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/groups/" + groupB + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void anyMemberCanEditAnotherMembersExpense() throws Exception {
        AuthResponse alice = register("ed-a7@example.com", "password123", "Alice");
        AuthResponse bob = register("ed-b7@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Shared");
        addMember(alice.accessToken(), g, "ed-b7@example.com");
        // Alice creates; Bob (a member, not the payer) edits — allowed under flat authorization.
        String id = createExpense(alice.accessToken(), g,
                new CreateExpenseRequest("Groceries", 1000L, alice.user().id(), null));
        mockMvc.perform(put("/api/groups/" + g + "/expenses/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateExpenseRequest("Groceries", 2000L, alice.user().id(), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountCents").value(2000));
    }
}
