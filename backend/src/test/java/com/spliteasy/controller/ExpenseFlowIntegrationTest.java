package com.spliteasy.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.AddMemberRequest;
import com.spliteasy.dto.AuthResponse;
import com.spliteasy.dto.CreateExpenseRequest;
import com.spliteasy.dto.CreateGroupRequest;
import com.spliteasy.dto.SplitInput;
import com.spliteasy.entity.SplitType;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@AutoConfigureMockMvc
class ExpenseFlowIntegrationTest extends AbstractIntegrationTest {

    private String createGroup(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(jsonPost("/api/groups", new CreateGroupRequest(name))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void addMember(String token, String groupId, String email) throws Exception {
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/members", new AddMemberRequest(email))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());
    }

    private UUID userId(AuthResponse auth) {
        return auth.user().id();
    }

    @Test
    void createExpenseSplitsEquallyAcrossAllMembersByDefault() throws Exception {
        AuthResponse owner = register("exp-owner1@example.com", "password123", "Owner");
        register("exp-b1@example.com", "password123", "Bee");
        register("exp-c1@example.com", "password123", "Cee");
        String groupId = createGroup(owner.accessToken(), "Goa");
        addMember(owner.accessToken(), groupId, "exp-b1@example.com");
        addMember(owner.accessToken(), groupId, "exp-c1@example.com");

        // 1000 cents / 3 members = 334/333/333, payer absorbs the extra cent.
        var body = new CreateExpenseRequest("Dinner", 1000L, userId(owner), null);
        MvcResult result = mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountCents").value(1000))
                .andExpect(jsonPath("$.paidBy.email").value("exp-owner1@example.com"))
                .andExpect(jsonPath("$.participants", hasSize(3)))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        long total = 0;
        long payerShare = -1;
        for (JsonNode p : json.get("participants")) {
            total += p.get("shareCents").asLong();
            if (p.get("user").get("email").asText().equals("exp-owner1@example.com")) {
                payerShare = p.get("shareCents").asLong();
            }
        }
        // Shares sum exactly to the total; payer absorbed the remainder cent.
        org.assertj.core.api.Assertions.assertThat(total).isEqualTo(1000L);
        org.assertj.core.api.Assertions.assertThat(payerShare).isEqualTo(334L);
    }

    @Test
    void createExpenseWithExplicitParticipantSubset() throws Exception {
        AuthResponse owner = register("exp-owner2@example.com", "password123", "Owner");
        AuthResponse bob = register("exp-b2@example.com", "password123", "Bob");
        register("exp-c2@example.com", "password123", "Cara");
        String groupId = createGroup(owner.accessToken(), "Subset");
        addMember(owner.accessToken(), groupId, "exp-b2@example.com");
        addMember(owner.accessToken(), groupId, "exp-c2@example.com");

        // Only owner + bob split 1000 → 500 each; Cara excluded.
        var body = new CreateExpenseRequest("Taxi", 1000L, userId(owner),
                List.of(userId(owner), userId(bob)));
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participants", hasSize(2)));
    }

    @Test
    void zeroAmountIsRejectedWith400() throws Exception {
        AuthResponse owner = register("exp-owner3@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "ZeroAmt");
        var body = new CreateExpenseRequest("Nothing", 0L, userId(owner), null);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmountIsRejectedWith400() throws Exception {
        AuthResponse owner = register("exp-owner4@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "NegAmt");
        var body = new CreateExpenseRequest("Refund", -500L, userId(owner), null);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void payerMustBeAGroupMember() throws Exception {
        AuthResponse owner = register("exp-owner5@example.com", "password123", "Owner");
        AuthResponse outsider = register("exp-out5@example.com", "password123", "Outsider");
        String groupId = createGroup(owner.accessToken(), "PayerCheck");
        var body = new CreateExpenseRequest("Weird", 500L, userId(outsider), null);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void participantMustBeAGroupMember() throws Exception {
        AuthResponse owner = register("exp-owner6@example.com", "password123", "Owner");
        AuthResponse outsider = register("exp-out6@example.com", "password123", "Outsider");
        String groupId = createGroup(owner.accessToken(), "PartCheck");
        var body = new CreateExpenseRequest("Weird", 500L, userId(owner),
                List.of(userId(owner), userId(outsider)));
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonMemberCannotCreateExpense() throws Exception {
        AuthResponse owner = register("exp-owner7@example.com", "password123", "Owner");
        AuthResponse outsider = register("exp-out7@example.com", "password123", "Outsider");
        String groupId = createGroup(owner.accessToken(), "Locked");
        var body = new CreateExpenseRequest("Sneaky", 500L, userId(outsider), null);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonMemberCannotListExpenses() throws Exception {
        AuthResponse owner = register("exp-owner8@example.com", "password123", "Owner");
        AuthResponse outsider = register("exp-out8@example.com", "password123", "Outsider");
        String groupId = createGroup(owner.accessToken(), "Hidden");
        mockMvc.perform(get("/api/groups/" + groupId + "/expenses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAndGetExpenseReturnDetails() throws Exception {
        AuthResponse owner = register("exp-owner9@example.com", "password123", "Owner");
        register("exp-b9@example.com", "password123", "Bee");
        String groupId = createGroup(owner.accessToken(), "History");
        addMember(owner.accessToken(), groupId, "exp-b9@example.com");

        var body = new CreateExpenseRequest("Groceries", 1001L, userId(owner), null);
        MvcResult created = mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andReturn();
        String expenseId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/groups/" + groupId + "/expenses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("Groceries"))
                .andExpect(jsonPath("$[0].participantCount").value(2))
                .andExpect(jsonPath("$[0].amountCents").value(1001));

        mockMvc.perform(get("/api/groups/" + groupId + "/expenses/" + expenseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(expenseId))
                .andExpect(jsonPath("$.participants", hasSize(2)));
    }

    @Test
    void getExpenseFromWrongGroupIs404() throws Exception {
        AuthResponse owner = register("exp-owner10@example.com", "password123", "Owner");
        String groupA = createGroup(owner.accessToken(), "GroupA");
        String groupB = createGroup(owner.accessToken(), "GroupB");
        var body = new CreateExpenseRequest("A-only", 300L, userId(owner), null);
        MvcResult created = mockMvc.perform(jsonPost("/api/groups/" + groupA + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andReturn();
        String expenseId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        // Same user, member of both groups, but expense belongs to A, queried under B.
        mockMvc.perform(get("/api/groups/" + groupB + "/expenses/" + expenseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void amountIsIntegerCentsNotFloatingPoint() throws Exception {
        // Guard: a large amount stays exact (no float precision loss).
        AuthResponse owner = register("exp-owner11@example.com", "password123", "Owner");
        String groupId = createGroup(owner.accessToken(), "BigMoney");
        long huge = 1_000_000_000_000L; // the @Max cap ($10B in cents); values above it are now rejected
        var body = new CreateExpenseRequest("Yacht", huge, userId(owner), null);
        mockMvc.perform(jsonPost("/api/groups/" + groupId + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountCents").value(huge))
                .andExpect(jsonPath("$.participants[0].shareCents").value(huge));
    }

    // --- UNEQUAL split ---

    @Test
    void unequalSplitThatSumsCorrectlyIsStored() throws Exception {
        AuthResponse owner = register("uneq-o1@example.com", "password123", "Owner");
        AuthResponse bob = register("uneq-b1@example.com", "password123", "Bob");
        String g = createGroup(owner.accessToken(), "Uneq");
        addMember(owner.accessToken(), g, "uneq-b1@example.com");

        // 1000c split 600/400.
        var body = new CreateExpenseRequest("Dinner", 1000L, userId(owner), null, SplitType.UNEQUAL,
                List.of(new SplitInput(userId(owner), 600L), new SplitInput(userId(bob), 400L)));
        MvcResult r = mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splitType").value("UNEQUAL"))
                .andReturn();
        JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
        long total = 0;
        for (JsonNode p : json.get("participants")) {
            total += p.get("shareCents").asLong();
        }
        assertThat(total).isEqualTo(1000L);
    }

    @Test
    void unequalSplitThatDoesNotSumIsRejected() throws Exception {
        AuthResponse owner = register("uneq-o2@example.com", "password123", "Owner");
        AuthResponse bob = register("uneq-b2@example.com", "password123", "Bob");
        String g = createGroup(owner.accessToken(), "Uneq2");
        addMember(owner.accessToken(), g, "uneq-b2@example.com");

        // 600 + 300 = 900 != 1000 → 400.
        var body = new CreateExpenseRequest("Dinner", 1000L, userId(owner), null, SplitType.UNEQUAL,
                List.of(new SplitInput(userId(owner), 600L), new SplitInput(userId(bob), 300L)));
        mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Entered amounts (900c) must add up to the expense total (1000c)")));
    }

    @Test
    void unequalSplitWithNonMemberParticipantIsRejected() throws Exception {
        AuthResponse owner = register("uneq-o3@example.com", "password123", "Owner");
        AuthResponse outsider = register("uneq-out3@example.com", "password123", "Out");
        String g = createGroup(owner.accessToken(), "Uneq3");
        var body = new CreateExpenseRequest("Dinner", 1000L, userId(owner), null, SplitType.UNEQUAL,
                List.of(new SplitInput(userId(owner), 500L), new SplitInput(userId(outsider), 500L)));
        mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest());
    }

    // --- PERCENTAGE split ---

    @Test
    void percentageSplitTo100IsStoredWithRounding() throws Exception {
        AuthResponse owner = register("pct-o1@example.com", "password123", "Owner");
        register("pct-b1@example.com", "password123", "Bob");
        register("pct-c1@example.com", "password123", "Carol");
        String g = createGroup(owner.accessToken(), "Pct");
        addMember(owner.accessToken(), g, "pct-b1@example.com");
        addMember(owner.accessToken(), g, "pct-c1@example.com");
        String bobId = memberId(owner.accessToken(), g, "pct-b1@example.com");
        String carolId = memberId(owner.accessToken(), g, "pct-c1@example.com");

        // 33.33/33.33/33.34% of 1000 → 333/333/334, payer absorbs.
        var body = new CreateExpenseRequest("Rent", 1000L, userId(owner), null, SplitType.PERCENTAGE,
                List.of(new SplitInput(userId(owner), 3333L),
                        new SplitInput(UUID.fromString(bobId), 3333L),
                        new SplitInput(UUID.fromString(carolId), 3334L)));
        MvcResult r = mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.splitType").value("PERCENTAGE"))
                .andReturn();
        JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
        long total = 0;
        for (JsonNode p : json.get("participants")) {
            total += p.get("shareCents").asLong();
        }
        assertThat(total).isEqualTo(1000L); // no cent lost to rounding
    }

    @Test
    void percentageSplitNotSummingTo100IsRejected() throws Exception {
        AuthResponse owner = register("pct-o2@example.com", "password123", "Owner");
        AuthResponse bob = register("pct-b2@example.com", "password123", "Bob");
        String g = createGroup(owner.accessToken(), "Pct2");
        addMember(owner.accessToken(), g, "pct-b2@example.com");

        // 50% + 40% = 90% → 400.
        var body = new CreateExpenseRequest("Rent", 1000L, userId(owner), null, SplitType.PERCENTAGE,
                List.of(new SplitInput(userId(owner), 5000L), new SplitInput(userId(bob), 4000L)));
        mockMvc.perform(jsonPost("/api/groups/" + g + "/expenses", body)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Percentages must add up to 100% (got 90.00%)")));
    }

    /** Looks up a member's user id from the group detail response (members carry id+email). */
    private String memberId(String token, String groupId, String email) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/groups/" + groupId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode m : objectMapper.readTree(r.getResponse().getContentAsString()).get("members")) {
            if (m.get("email").asText().equals(email)) {
                return m.get("id").asText();
            }
        }
        throw new AssertionError("member not found: " + email);
    }
}
