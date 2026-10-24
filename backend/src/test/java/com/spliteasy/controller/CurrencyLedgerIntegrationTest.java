package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;

@AutoConfigureMockMvc
class CurrencyLedgerIntegrationTest extends AbstractIntegrationTest {
  @Test
  void legacyUsdExpenseHasExplicitDenominationWithoutChangingItsAmount() throws Exception {
    var alice = register("currency-usd@example.test", "password123", "Alice");
    var group =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Currency"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    String groupId =
        objectMapper.readTree(group.getResponse().getContentAsString()).get("id").asText();
    var result =
        mockMvc
            .perform(
                jsonPost(
                        "/api/groups/" + groupId + "/expenses",
                        Map.of(
                            "description",
                            "Old USD writer",
                            "amountCents",
                            1234,
                            "paidByUserId",
                            alice.user().id()))
                    .header(HttpHeaders.AUTHORIZATION, bearer(alice.accessToken())))
            .andExpect(status().isCreated())
            .andReturn();
    var expense = objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(expense.path("currency").asText()).isEqualTo("USD");
    assertThat(expense.path("amountMinor").asLong()).isEqualTo(1234);
    assertThat(expense.path("amountCents").asLong()).isEqualTo(1234);
    assertThat(expense.path("participants").get(0).path("shareMinor").asLong()).isEqualTo(1234);
  }
}
