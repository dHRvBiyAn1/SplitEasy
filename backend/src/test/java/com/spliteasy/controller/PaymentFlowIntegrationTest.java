package com.spliteasy.controller;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.payment.RecordPaymentRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

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

    private void addExpense(String token, String groupId, String desc, long cents, UUID payer) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses",
                        new CreateExpenseRequest(desc, cents, payer, null))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());
    }

    private void pay(String token, String groupId, UUID payer, UUID payee, long cents) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/payments",
                        new RecordPaymentRequest(payer, payee, cents))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated());
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
    void paymentReducesPayersDebtToPayee() throws Exception {
        AuthResponse alice = register("pay-a1@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b1@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip");
        addMember(alice.accessToken(), g, "pay-b1@example.com");

        // Alice pays $10 split 2 → Bob owes Alice 500. Bob -500, Alice +500.
        addExpense(alice.accessToken(), g, "Dinner", 1000, alice.user().id());
        assertThat(balances(alice.accessToken(), g).get("pay-b1@example.com")).isEqualTo(-500);

        // Bob pays Alice 200 → Bob's debt shrinks to 300.
        pay(bob.accessToken(), g, bob.user().id(), alice.user().id(), 200);
        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("pay-b1@example.com")).isEqualTo(-300);
        assertThat(bal.get("pay-a1@example.com")).isEqualTo(300);
        assertThat(sum(bal)).isZero();
    }

    @Test
    void fullSettleBringsPairToExactlyZero() throws Exception {
        AuthResponse alice = register("pay-a2@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b2@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip2");
        addMember(alice.accessToken(), g, "pay-b2@example.com");

        addExpense(alice.accessToken(), g, "Dinner", 1000, alice.user().id()); // Bob owes 500
        pay(bob.accessToken(), g, bob.user().id(), alice.user().id(), 500);     // settle exactly

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("pay-a2@example.com")).isZero();
        assertThat(bal.get("pay-b2@example.com")).isZero();
    }

    @Test
    void zeroSumHoldsWithMixedExpensesAndPayments() throws Exception {
        AuthResponse alice = register("pay-a3@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b3@example.com", "password123", "Bob");
        AuthResponse carol = register("pay-c3@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip3");
        addMember(alice.accessToken(), g, "pay-b3@example.com");
        addMember(alice.accessToken(), g, "pay-c3@example.com");

        // Two expenses (different payers) + two payments intermixed.
        addExpense(alice.accessToken(), g, "Hotel", 900, alice.user().id());   // 300 each
        addExpense(bob.accessToken(), g, "Gas", 600, bob.user().id());         // 200 each
        pay(bob.accessToken(), g, bob.user().id(), alice.user().id(), 100);
        pay(carol.accessToken(), g, carol.user().id(), alice.user().id(), 250);

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal).hasSize(3);
        assertThat(sum(bal)).isZero(); // zero-sum survives expenses + payments together
    }

    @Test
    void overpaymentIsAllowedAndFlipsTheSign() throws Exception {
        AuthResponse alice = register("pay-a4@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b4@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip4");
        addMember(alice.accessToken(), g, "pay-b4@example.com");

        addExpense(alice.accessToken(), g, "Dinner", 1000, alice.user().id()); // Bob owes 500
        // Bob pays 800 — more than the 500 he owes. Now Alice owes Bob 300.
        pay(bob.accessToken(), g, bob.user().id(), alice.user().id(), 800);

        Map<String, Long> bal = balances(alice.accessToken(), g);
        assertThat(bal.get("pay-b4@example.com")).isEqualTo(300);   // now owed money
        assertThat(bal.get("pay-a4@example.com")).isEqualTo(-300);  // now owes
        assertThat(sum(bal)).isZero();
    }

    @Test
    void paymentToSelfIsRejected() throws Exception {
        AuthResponse alice = register("pay-a5@example.com", "password123", "Alice");
        String g = createGroup(alice.accessToken(), "Trip5");
        mockMvc.perform(jsonPost("/api/groups/" + g + "/payments",
                        new RecordPaymentRequest(alice.user().id(), alice.user().id(), 100))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonPositiveAmountIsRejected() throws Exception {
        AuthResponse alice = register("pay-a6@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b6@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip6");
        addMember(alice.accessToken(), g, "pay-b6@example.com");
        mockMvc.perform(jsonPost("/api/groups/" + g + "/payments",
                        new RecordPaymentRequest(bob.user().id(), alice.user().id(), 0))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void amountAboveTheCapIsRejected() throws Exception {
        AuthResponse alice = register("pay-a10@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b10@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip10");
        addMember(alice.accessToken(), g, "pay-b10@example.com");
        // One cent over the $10B cap → 400 from @Max validation.
        mockMvc.perform(jsonPost("/api/groups/" + g + "/payments",
                        new RecordPaymentRequest(bob.user().id(), alice.user().id(), 1_000_000_000_001L))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payeeMustBeAGroupMember() throws Exception {
        AuthResponse alice = register("pay-a7@example.com", "password123", "Alice");
        AuthResponse outsider = register("pay-out7@example.com", "password123", "Outsider");
        String g = createGroup(alice.accessToken(), "Trip7");
        mockMvc.perform(jsonPost("/api/groups/" + g + "/payments",
                        new RecordPaymentRequest(alice.user().id(), outsider.user().id(), 100))
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMemberCannotRecordOrListPayments() throws Exception {
        AuthResponse alice = register("pay-a8@example.com", "password123", "Alice");
        AuthResponse outsider = register("pay-out8@example.com", "password123", "Outsider");
        String g = createGroup(alice.accessToken(), "Trip8");
        mockMvc.perform(jsonPost("/api/groups/" + g + "/payments",
                        new RecordPaymentRequest(alice.user().id(), alice.user().id(), 100))
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/groups/" + g + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsPaymentHistoryNewestFirst() throws Exception {
        AuthResponse alice = register("pay-a9@example.com", "password123", "Alice");
        AuthResponse bob = register("pay-b9@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Trip9");
        addMember(alice.accessToken(), g, "pay-b9@example.com");
        pay(bob.accessToken(), g, bob.user().id(), alice.user().id(), 100);
        pay(alice.accessToken(), g, alice.user().id(), bob.user().id(), 250);

        mockMvc.perform(get("/api/groups/" + g + "/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amountCents").value(250))   // newest first
                .andExpect(jsonPath("$[0].payer.email").value("pay-a9@example.com"))
                .andExpect(jsonPath("$[0].payee.email").value("pay-b9@example.com"))
                .andExpect(jsonPath("$[1].amountCents").value(100));
    }
}
