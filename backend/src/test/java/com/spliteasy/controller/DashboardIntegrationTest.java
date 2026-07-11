package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.CreateGroupRequest;
import com.spliteasy.entity.ExpenseCategory;
import com.spliteasy.entity.GroupType;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class DashboardIntegrationTest extends AbstractIntegrationTest {

    private String createGroup(String token, CreateGroupRequest req) throws Exception {
        MvcResult r = mockMvc.perform(jsonPost("/api/groups", req)
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

    private void addExpense(String token, String groupId, CreateExpenseRequest body) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());
    }

    private JsonNode dashboard(String token) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void dashboardShowsPairwiseOwedAndOweWithoutNetting() throws Exception {
        AuthResponse alice = register("dash-a@example.com", "password123", "Alice");
        register("dash-b@example.com", "password123", "Bob");
        register("dash-c@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), new CreateGroupRequest("Trip", GroupType.TRIP));
        addMember(alice.accessToken(), g, "dash-b@example.com");
        addMember(alice.accessToken(), g, "dash-c@example.com");

        AuthResponse bob = login("dash-b@example.com", "password123");
        // Alice pays 300 split with Carol (150 each) → Carol owes Alice 150.
        addExpense(alice.accessToken(), g, new CreateExpenseRequest(
                "Lunch", 300, alice.user().id(), List.of(alice.user().id(), carolId())));
        // Bob pays 300 split with Alice (150 each) → Alice owes Bob 150.
        addExpense(bob.accessToken(), g, new CreateExpenseRequest(
                "Cab", 300, bob.user().id(), List.of(bob.user().id(), alice.user().id())));

        JsonNode d = dashboard(alice.accessToken());

        // Pairwise, NOT netted: Alice is owed 150 (by Carol) AND owes 150 (to Bob).
        assertThat(d.get("owedCents").asLong()).isEqualTo(150);
        assertThat(d.get("owedPeopleCount").asInt()).isEqualTo(1);
        assertThat(d.get("oweCents").asLong()).isEqualTo(150);
        assertThat(d.get("owePeopleCount").asInt()).isEqualTo(1);
        assertThat(d.get("totalNetCents").asLong()).isZero();
        assertThat(d.get("groupCount").asInt()).isEqualTo(1);

        Map<String, Long> people = byName(d.get("people"));
        assertThat(people.get("Carol")).isEqualTo(150);
        assertThat(people.get("Bob")).isEqualTo(-150);

        JsonNode card = d.get("groups").get(0);
        assertThat(card.get("youAreOwedCents").asLong()).isEqualTo(150);
        assertThat(card.get("youOweCents").asLong()).isEqualTo(150);
        assertThat(card.get("netCents").asLong()).isZero();
        assertThat(card.get("totalSpentCents").asLong()).isEqualTo(600);
        assertThat(card.get("memberCount").asLong()).isEqualTo(3);
        assertThat(card.get("type").asText()).isEqualTo("TRIP");

        // Two expenses in the activity feed with the viewer's lent/borrowed delta.
        Map<String, Long> deltas = new HashMap<>();
        for (JsonNode a : d.get("activity")) {
            deltas.put(a.get("description").asText(), a.get("viewerDeltaCents").asLong());
        }
        assertThat(deltas.get("Lunch")).isEqualTo(150); // Alice paid, lent
        assertThat(deltas.get("Cab")).isEqualTo(-150); // Bob paid, Alice borrowed
    }

    @Test
    void groupPairwiseBalancesEndpointMatchesDashboard() throws Exception {
        AuthResponse alice = register("dash2-a@example.com", "password123", "Alice");
        register("dash2-b@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), new CreateGroupRequest("Flat"));
        addMember(alice.accessToken(), g, "dash2-b@example.com");
        // Alice pays 400 split with Bob → Bob owes Alice 200.
        addExpense(alice.accessToken(), g, new CreateExpenseRequest("Rent", 400, alice.user().id(), null));

        MvcResult r = mockMvc.perform(get("/api/groups/" + g + "/balances/mine")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode mine = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("user").get("displayName").asText()).isEqualTo("Bob");
        assertThat(mine.get(0).get("netCents").asLong()).isEqualTo(200);
    }

    @Test
    void expenseCategoryAndDatePersist() throws Exception {
        AuthResponse alice = register("dash3-a@example.com", "password123", "Alice");
        String g = createGroup(alice.accessToken(), new CreateGroupRequest("Solo", GroupType.HOME));
        LocalDate when = LocalDate.of(2026, 5, 20);
        MvcResult r = mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses",
                        new CreateExpenseRequest("Groceries", 500, alice.user().id(), null, null, null,
                                ExpenseCategory.GROCERIES, when))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode e = objectMapper.readTree(r.getResponse().getContentAsString());
        assertThat(e.get("category").asText()).isEqualTo("GROCERIES");
        assertThat(e.get("spentOn").asText()).isEqualTo("2026-05-20");
    }

    @Test
    void unauthenticatedDashboardRejected() throws Exception {
        mockMvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    private Map<String, Long> byName(JsonNode people) {
        Map<String, Long> out = new HashMap<>();
        for (JsonNode p : people) {
            out.put(p.get("user").get("displayName").asText(), p.get("netCents").asLong());
        }
        return out;
    }

    private UUID carolId;

    private UUID carolId() {
        // Carol's id resolved lazily from a fresh login (registration returns only the caller's id).
        if (carolId == null) {
            try {
                carolId = login("dash-c@example.com", "password123").user().id();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        return carolId;
    }
}
