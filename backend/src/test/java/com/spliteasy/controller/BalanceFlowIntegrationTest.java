package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.CreateGroupRequest;
import com.spliteasy.dto.SplitInput;
import com.spliteasy.entity.SplitType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureMockMvc
class BalanceFlowIntegrationTest extends AbstractIntegrationTest {

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

    private void addExpense(String token, String groupId, String desc, long cents, UUID payer, List<UUID> parts)
            throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses",
                        new CreateExpenseRequest(desc, cents, payer, parts))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());
    }

    /** Fetches balances and returns a map of email -> netCents. */
    private Map<String, Long> balances(String token, String groupId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/groups/" + groupId + "/balances")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(r.getResponse().getContentAsString());
        Map<String, Long> byEmail = new HashMap<>();
        for (JsonNode b : root.get("balances")) {
            byEmail.put(b.get("user").get("email").asText(), b.get("netCents").asLong());
        }
        return byEmail;
    }

    private static long sum(Map<String, Long> balances) {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }

    @Test
    void singleExpensePayerIsOwedParticipantsOwe() throws Exception {
        AuthResponse alice = register("bal-a1@example.com", "password123", "Alice");
        register("bal-b1@example.com", "password123", "Bob");
        register("bal-c1@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip");
        addMember(alice.accessToken(), g, "bal-b1@example.com");
        addMember(alice.accessToken(), g, "bal-c1@example.com");

        // Alice pays 900, split 3 ways → 300 each. Alice +600, others -300.
        addExpense(alice.accessToken(), g, "Hotel", 900, alice.user().id(), null);

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("bal-a1@example.com")).isEqualTo(600);
        assertThat(bal.get("bal-b1@example.com")).isEqualTo(-300);
        assertThat(bal.get("bal-c1@example.com")).isEqualTo(-300);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void multipleExpensesMultiplePayersNetOffset() throws Exception {
        AuthResponse alice = register("bal-a2@example.com", "password123", "Alice");
        AuthResponse bob = register("bal-b2@example.com", "password123", "Bob");
        register("bal-c2@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip2");
        addMember(alice.accessToken(), g, "bal-b2@example.com");
        addMember(alice.accessToken(), g, "bal-c2@example.com");

        // Alice pays 900 (300 each); Bob pays 600 (200 each). All split 3 ways.
        addExpense(alice.accessToken(), g, "Hotel", 900, alice.user().id(), null);
        addExpense(bob.accessToken(), g, "Dinner", 600, bob.user().id(), null);

        // Alice: paid 900, owed 500 → +400. Bob: paid 600, owed 500 → +100. Carol: -500.
        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("bal-a2@example.com")).isEqualTo(400);
        assertThat(bal.get("bal-b2@example.com")).isEqualTo(100);
        assertThat(bal.get("bal-c2@example.com")).isEqualTo(-500);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void memberCanHaveExactlyZeroNetBalance() throws Exception {
        AuthResponse alice = register("bal-a3@example.com", "password123", "Alice");
        AuthResponse bob = register("bal-b3@example.com", "password123", "Bob");
        register("bal-c3@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip3");
        addMember(alice.accessToken(), g, "bal-b3@example.com");
        addMember(alice.accessToken(), g, "bal-c3@example.com");

        // Alice pays 300 (100 each), Bob pays 150 (50 each), both split 3 ways.
        // Owed each = 150. Alice paid 300 → +150. Bob paid 150 → 0. Carol → -150.
        addExpense(alice.accessToken(), g, "Cab", 300, alice.user().id(), null);
        addExpense(bob.accessToken(), g, "Snacks", 150, bob.user().id(), null);

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("bal-a3@example.com")).isEqualTo(150);
        assertThat(bal.get("bal-b3@example.com")).isZero();
        assertThat(bal.get("bal-c3@example.com")).isEqualTo(-150);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void zeroSumInvariantHoldsWithRemainderCents() throws Exception {
        // Odd amounts that don't divide evenly still net to exactly zero.
        AuthResponse alice = register("bal-a4@example.com", "password123", "Alice");
        AuthResponse bob = register("bal-b4@example.com", "password123", "Bob");
        AuthResponse carol = register("bal-c4@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip4");
        addMember(alice.accessToken(), g, "bal-b4@example.com");
        addMember(alice.accessToken(), g, "bal-c4@example.com");

        addExpense(alice.accessToken(), g, "E1", 1001, alice.user().id(), null); // remainder 2
        addExpense(bob.accessToken(), g, "E2", 1000, bob.user().id(), null); // remainder 1
        addExpense(carol.accessToken(), g, "E3", 777, carol.user().id(), null); // remainder 0
        addExpense(alice.accessToken(), g, "E4", 55, alice.user().id(),
                List.of(alice.user().id(), bob.user().id())); // subset, remainder 1

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal).hasSize(3);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void emptyGroupHasAllZeroBalances() throws Exception {
        AuthResponse alice = register("bal-a5@example.com", "password123", "Alice");
        register("bal-b5@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Empty");
        addMember(alice.accessToken(), g, "bal-b5@example.com");

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.values()).allMatch(v -> v == 0L);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void nonMemberCannotViewBalances() throws Exception {
        AuthResponse alice = register("bal-a6@example.com", "password123", "Alice");
        AuthResponse outsider = register("bal-out6@example.com", "password123", "Outsider");
        String g = createGroup(alice.accessToken(), "Private");
        mockMvc.perform(get("/api/groups/" + g + "/balances")
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        AuthResponse alice = register("bal-a7@example.com", "password123", "Alice");
        String g = createGroup(alice.accessToken(), "Auth");
        mockMvc.perform(get("/api/groups/" + g + "/balances"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void balancesNetToZeroAcrossMixedSplitTypes() throws Exception {
        AuthResponse a = register("bal-mix-a@example.com", "password123", "Alice");
        AuthResponse b = register("bal-mix-b@example.com", "password123", "Bob");
        AuthResponse c = register("bal-mix-c@example.com", "password123", "Carol");
        String g = createGroup(a.accessToken(), "Mixed");
        addMember(a.accessToken(), g, "bal-mix-b@example.com");
        addMember(a.accessToken(), g, "bal-mix-c@example.com");

        // EQUAL: Alice pays 900, all three.
        addExpense(a.accessToken(), g, "Hotel", 900, a.user().id(), null);
        // UNEQUAL: Bob pays 1000, split 700/300 between Bob and Carol.
        post(b.accessToken(), g, new CreateExpenseRequest("Gear", 1000L, b.user().id(), null, SplitType.UNEQUAL,
                List.of(new SplitInput(b.user().id(), 700L), new SplitInput(c.user().id(), 300L))));
        // PERCENTAGE: Carol pays 1000, 33.33/33.33/33.34 across all three (remainder-prone).
        post(c.accessToken(), g, new CreateExpenseRequest("Food", 1000L, c.user().id(), null, SplitType.PERCENTAGE,
                List.of(new SplitInput(a.user().id(), 3333L), new SplitInput(b.user().id(), 3333L),
                        new SplitInput(c.user().id(), 3334L))));

        Map<String, Long> bal = balances(a.accessToken(), g);
        assertThat(bal).hasSize(3);
        assertThat(sum(bal)).isZero(); // zero-sum invariant holds across all three split types
    }

    private void post(String token, String groupId, CreateExpenseRequest body) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());
    }
}
