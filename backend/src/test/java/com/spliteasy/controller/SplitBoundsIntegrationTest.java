package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.expense.CreateExpenseRequest;
import com.spliteasy.dto.expense.ExpenseResponse;
import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.entity.SplitType;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SplitBoundsIntegrationTest extends AbstractIntegrationTest {
  private String authorization;
  private String expensesUrl;
  private UUID ownerId;
  private UUID secondId;
  private UUID thirdId;

  @BeforeEach
  void createThreeMemberGroup() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var owner = register("split-owner-" + suffix + "@example.com", "password123", "Owner");
    var second = register("split-second-" + suffix + "@example.com", "password123", "Second");
    var third = register("split-third-" + suffix + "@example.com", "password123", "Third");
    authorization = bearer(owner.accessToken());
    ownerId = owner.user().id();
    secondId = second.user().id();
    thirdId = third.user().id();
    var result =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Split bounds"))
                    .header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isCreated())
            .andReturn();
    String groupUrl =
        "/api/groups/"
            + objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    for (var member : List.of(second, third)) {
      mockMvc
          .perform(
              jsonPost(groupUrl + "/members", new AddMemberRequest(member.user().email()))
                  .header(HttpHeaders.AUTHORIZATION, authorization))
          .andExpect(status().isOk());
    }
    expensesUrl = groupUrl + "/expenses";
  }

  private CreateExpenseRequest request(SplitType type, long amount, long a, long b, long c) {
    return new CreateExpenseRequest(
        "Split bounds",
        amount,
        ownerId,
        null,
        type,
        List.of(
            new SplitInput(ownerId, a), new SplitInput(secondId, b), new SplitInput(thirdId, c)));
  }

  private ExpenseResponse expense(MvcResult result) throws Exception {
    return objectMapper.readValue(result.getResponse().getContentAsString(), ExpenseResponse.class);
  }

  private String read(String url) throws Exception {
    return mockMvc
        .perform(get(url).header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @ParameterizedTest
  @CsvSource({"UNEQUAL,POST", "UNEQUAL,PUT", "PERCENTAGE,POST", "PERCENTAGE,PUT"})
  void invalidSplitsRejectCreateAndReplaceWithoutChangingTheLedger(SplitType type, String method)
      throws Exception {
    var original =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, new CreateExpenseRequest("Original", 900, ownerId, null))
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    String detailUrl = expensesUrl + "/" + original.id();
    String listBefore = read(expensesUrl);
    String detailBefore = read(detailUrl);
    String balancesUrl = expensesUrl.replace("/expenses", "/balances");
    String balancesBefore = read(balancesUrl);
    long target = type == SplitType.UNEQUAL ? 1000 : 10000;
    for (long[] values :
        List.of(
            new long[] {Long.MAX_VALUE, Long.MAX_VALUE, target + 2},
            new long[] {Long.MIN_VALUE, Long.MIN_VALUE, target},
            new long[] {-1, target + 1, 0},
            new long[] {target + 1, 0, 0},
            new long[] {target, target, 0},
            new long[] {target - 1, 0, 0})) {
      var invalid = request(type, 1000, values[0], values[1], values[2]);
      var invalidRequest =
          method.equals("POST")
              ? jsonPost(expensesUrl, invalid)
              : put(detailUrl).contentType(MediaType.APPLICATION_JSON).content(json(invalid));
      mockMvc
          .perform(invalidRequest.header(HttpHeaders.AUTHORIZATION, authorization))
          .andExpect(status().isBadRequest());
      assertThat(read(expensesUrl)).isEqualTo(listBefore);
      assertThat(read(detailUrl)).isEqualTo(detailBefore);
      assertThat(read(balancesUrl)).isEqualTo(balancesBefore);
    }
  }

  @ParameterizedTest
  @CsvSource({"UNEQUAL,POST", "UNEQUAL,PUT", "PERCENTAGE,POST", "PERCENTAGE,PUT"})
  void nullSplitEntriesAreRejectedWithoutChangingTheLedger(SplitType type, String method)
      throws Exception {
    var original =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, new CreateExpenseRequest("Original", 900, ownerId, null))
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    String detailUrl = expensesUrl + "/" + original.id();
    String listBefore = read(expensesUrl);
    String detailBefore = read(detailUrl);
    String balancesUrl = expensesUrl.replace("/expenses", "/balances");
    String balancesBefore = read(balancesUrl);
    long share = type == SplitType.UNEQUAL ? 500 : 5000;
    for (List<SplitInput> splits :
        List.of(
            Arrays.asList((SplitInput) null),
            Arrays.asList(new SplitInput(ownerId, share), new SplitInput(secondId, share), null))) {
      var invalid = new CreateExpenseRequest("Changed", 1000, ownerId, null, type, splits);
      var invalidRequest =
          method.equals("POST")
              ? jsonPost(expensesUrl, invalid)
              : put(detailUrl).contentType(MediaType.APPLICATION_JSON).content(json(invalid));

      mockMvc
          .perform(invalidRequest.header(HttpHeaders.AUTHORIZATION, authorization))
          .andExpect(status().isBadRequest());
      assertThat(read(expensesUrl)).isEqualTo(listBefore);
      assertThat(read(detailUrl)).isEqualTo(detailBefore);
      assertThat(read(balancesUrl)).isEqualTo(balancesBefore);
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = SplitType.class,
      names = {"UNEQUAL", "PERCENTAGE"})
  void maximumAmountSupportsBoundarySharesOnCreateAndReplaceButRejectsAboveCap(SplitType type)
      throws Exception {
    long cap = 1_000_000_000_000L;
    long full = type == SplitType.UNEQUAL ? cap : 10000;
    var created =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, request(type, cap, 0, full, 0))
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    assertShares(created, cap, secondId);
    String detailUrl = expensesUrl + "/" + created.id();
    var replaced =
        expense(
            mockMvc
                .perform(
                    put(detailUrl)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(type, cap, full, 0, 0))))
                .andExpect(status().isOk())
                .andReturn());
    assertShares(replaced, cap, ownerId);
    assertShares(objectMapper.readValue(read(detailUrl), ExpenseResponse.class), cap, ownerId);
    var overCap = request(type, cap + 1, type == SplitType.UNEQUAL ? cap + 1 : full, 0, 0);
    mockMvc
        .perform(jsonPost(expensesUrl, overCap).header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put(detailUrl)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(overCap)))
        .andExpect(status().isBadRequest());
    assertThat(objectMapper.readValue(read(detailUrl), ExpenseResponse.class)).isEqualTo(replaced);
  }

  private void assertShares(ExpenseResponse expense, long total, UUID fullShareUser) {
    assertThat(expense.amountCents()).isEqualTo(total);
    assertThat(expense.participants())
        .hasSize(3)
        .allSatisfy(
            p ->
                assertThat(p.shareCents())
                    .isEqualTo(p.user().id().equals(fullShareUser) ? total : 0));
    assertThat(
            expense.participants().stream()
                .map(p -> BigInteger.valueOf(p.shareCents()))
                .reduce(BigInteger.ZERO, BigInteger::add))
        .isEqualTo(BigInteger.valueOf(total));
  }
}
