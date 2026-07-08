package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.CreateGroupRequest;
import java.util.ArrayList;
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
class DebtSimplificationIntegrationTest extends AbstractIntegrationTest {

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

    /** email -> netCents from the balances endpoint. */
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

    private record Txn(String fromEmail, String toEmail, long amountCents) {}

    private List<Txn> simplified(String token, String groupId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/groups/" + groupId + "/debt-simplification")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        List<Txn> out = new ArrayList<>();
        for (JsonNode t : objectMapper.readTree(r.getResponse().getContentAsString()).get("transactions")) {
            out.add(new Txn(
                    t.get("from").get("email").asText(),
                    t.get("to").get("email").asText(),
                    t.get("amountCents").asLong()));
        }
        return out;
    }

    /** Apply the transfers to the current balances; returns each member's resulting net. */
    private static Map<String, Long> applyToBalances(Map<String, Long> balances, List<Txn> txns) {
        Map<String, Long> net = new HashMap<>(balances);
        for (Txn t : txns) {
            net.merge(t.fromEmail(), t.amountCents(), Long::sum); // debtor pays -> net rises toward 0
            net.merge(t.toEmail(), -t.amountCents(), Long::sum); // creditor paid -> net falls toward 0
        }
        return net;
    }

    @Test
    void simplifiesToFewerTransactionsThanNonZeroBalancesAndSettlesToZero() throws Exception {
        AuthResponse alice = register("ds-a1@example.com", "password123", "Alice");
        AuthResponse bob = register("ds-b1@example.com", "password123", "Bob");
        AuthResponse carol = register("ds-c1@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Trip");
        addMember(alice.accessToken(), g, "ds-b1@example.com");
        addMember(alice.accessToken(), g, "ds-c1@example.com");

        // Two 3-way expenses with different payers → 3 non-zero balances that collapse.
        addExpense(alice.accessToken(), g, "Hotel", 900, alice.user().id()); // 300 each
        addExpense(bob.accessToken(), g, "Gas", 300, bob.user().id()); // 100 each
        // Nets: Alice +500, Bob -100, Carol -400 (sum 0).

        Map<String, Long> bal = balances(alice.accessToken(), g);
        long nonZero = bal.values().stream().filter(v -> v != 0).count();
        assertThat(nonZero).isEqualTo(3);

        List<Txn> txns = simplified(alice.accessToken(), g);

        // Greedy collapses 3 non-zero balances into 2 transfers (fewer than the pairwise list).
        assertThat(txns).hasSize(2);
        assertThat((long) txns.size()).isLessThan(nonZero);
        assertThat(txns).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
        // Applying the plan settles everyone to exactly zero — no leftover cents.
        assertThat(applyToBalances(bal, txns).values()).allMatch(v -> v == 0L);
        long total = txns.stream().mapToLong(Txn::amountCents).sum();
        assertThat(total).isEqualTo(500); // == total positive balance
    }

    @Test
    void fullySettledGroupHasNoSuggestedTransactions() throws Exception {
        AuthResponse alice = register("ds-a2@example.com", "password123", "Alice");
        register("ds-b2@example.com", "password123", "Bob");
        String g = createGroup(alice.accessToken(), "Quiet");
        addMember(alice.accessToken(), g, "ds-b2@example.com");
        // No expenses → everyone settled.
        assertThat(simplified(alice.accessToken(), g)).isEmpty();
    }

    @Test
    void oddSplitWithRemainderStillSettlesToZeroWithNoLeftoverCents() throws Exception {
        AuthResponse alice = register("ds-a3@example.com", "password123", "Alice");
        register("ds-b3@example.com", "password123", "Bob");
        register("ds-c3@example.com", "password123", "Carol");
        String g = createGroup(alice.accessToken(), "Odd");
        addMember(alice.accessToken(), g, "ds-b3@example.com");
        addMember(alice.accessToken(), g, "ds-c3@example.com");

        // 1000 split 3 ways → 333/333/334 (payer absorbs the remainder).
        addExpense(alice.accessToken(), g, "Dinner", 1000, alice.user().id());

        Map<String, Long> bal = balances(alice.accessToken(), g);
        List<Txn> txns = simplified(alice.accessToken(), g);

        assertThat(txns).allSatisfy(t -> assertThat(t.amountCents()).isPositive());
        assertThat(applyToBalances(bal, txns).values()).allMatch(v -> v == 0L);
        long total = txns.stream().mapToLong(Txn::amountCents).sum();
        assertThat(total).isEqualTo(bal.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum());
    }

    @Test
    void nonMemberCannotViewSimplifiedDebts() throws Exception {
        AuthResponse alice = register("ds-a4@example.com", "password123", "Alice");
        AuthResponse outsider = register("ds-out4@example.com", "password123", "Outsider");
        String g = createGroup(alice.accessToken(), "Private");
        mockMvc.perform(get("/api/groups/" + g + "/debt-simplification")
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }
}
