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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class SplitIntentIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

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
                jsonPost("/api/groups", new CreateGroupRequest("Split intent"))
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
        "Split intent",
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

  @org.junit.jupiter.api.Test
  void tinyPercentageIntentSurvivesReadDescriptionEditAndAmountChange() throws Exception {
    var create = request(SplitType.PERCENTAGE, 1, 5000, 5000, 0);
    var original =
        mockMvc
            .perform(jsonPost(expensesUrl, create).header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isCreated())
            .andReturn();
    var tree = objectMapper.readTree(original.getResponse().getContentAsString());
    assertThat(tree.get("splits")).isNotNull();
    assertThat(tree.get("splits").toString()).contains("5000");
    String detail = expensesUrl + "/" + tree.get("id").asText();
    assertThat(objectMapper.readTree(read(detail)).get("splits")).isEqualTo(tree.get("splits"));
    for (long amount : List.of(1L, 100L)) {
      var changed =
          new CreateExpenseRequest(
              "Description changed", amount, ownerId, null, SplitType.PERCENTAGE, create.splits());
      var replaced =
          expense(
              mockMvc
                  .perform(
                      put(detail)
                          .header(HttpHeaders.AUTHORIZATION, authorization)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(json(changed)))
                  .andExpect(status().isOk())
                  .andReturn());
      assertThat(replaced.splitType()).isEqualTo(SplitType.PERCENTAGE);
      assertThat(replaced.participants())
          .hasSize(3)
          .allSatisfy(
              p ->
                  assertThat(p.shareCents())
                      .isEqualTo(
                          p.user().id().equals(thirdId)
                              ? 0
                              : amount == 1 ? (p.user().id().equals(ownerId) ? 1 : 0) : 50));
      assertThat(objectMapper.readTree(read(detail)).get("splits")).isEqualTo(tree.get("splits"));
    }
  }

  @org.junit.jupiter.api.Test
  void weightedIntentUsesIntegerUnitsAndRecomputesAfterAmountChange() throws Exception {
    var input =
        java.util.Map.of(
            "description",
            "Weighted",
            "amountCents",
            5,
            "paidByUserId",
            ownerId,
            "splitType",
            "WEIGHTS",
            "splits",
            List.of(new SplitInput(ownerId, 1), new SplitInput(secondId, 2)));
    var created =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, input).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(created.participants())
        .hasSize(2)
        .allSatisfy(
            p -> assertThat(p.shareCents()).isEqualTo(p.user().id().equals(ownerId) ? 2 : 3));
    String detail = expensesUrl + "/" + created.id();
    var updated = new java.util.HashMap<String, Object>(input);
    updated.put("amountCents", 10);
    var changed =
        expense(
            mockMvc
                .perform(
                    put(detail)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(changed.participants())
        .hasSize(2)
        .allSatisfy(
            p -> assertThat(p.shareCents()).isEqualTo(p.user().id().equals(ownerId) ? 4 : 6));
    assertThat(objectMapper.readTree(read(detail)).get("splits").toString())
        .contains("\"value\":1", "\"value\":2");
  }

  @org.junit.jupiter.api.Test
  void signedAdjustmentsApplyToEqualAllocationAndRetainZeros() throws Exception {
    var input =
        java.util.Map.of(
            "description",
            "Adjusted",
            "amountCents",
            5,
            "paidByUserId",
            ownerId,
            "splitType",
            "ADJUSTMENTS",
            "splits",
            List.of(
                new SplitInput(ownerId, -1),
                new SplitInput(secondId, 1),
                new SplitInput(thirdId, 0)));
    var created =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, input).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(created.participants())
        .hasSize(3)
        .allSatisfy(
            p -> assertThat(p.shareCents()).isEqualTo(p.user().id().equals(thirdId) ? 1 : 2));
    String detail = expensesUrl + "/" + created.id();
    var updated = new java.util.HashMap<String, Object>(input);
    updated.put("amountCents", 1);
    var changed =
        expense(
            mockMvc
                .perform(
                    put(detail)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(updated)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(changed.participants())
        .hasSize(3)
        .allSatisfy(
            p -> assertThat(p.shareCents()).isEqualTo(p.user().id().equals(secondId) ? 1 : 0));
    assertThat(objectMapper.readTree(read(detail)).get("splits").toString())
        .contains("\"value\":-1", "\"value\":1", "\"value\":0");
  }

  @org.junit.jupiter.api.Test
  void oldWriterWithoutIntentIsReadAndEditedAsExactShares() throws Exception {
    var original =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, request(SplitType.PERCENTAGE, 1, 5000, 5000, 0))
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    // Fixture only: reproduce an older writer after V11; all assertions use public HTTP reads.
    jdbc.update(
        "update expense_participants set split_value = null where expense_id = ?", original.id());
    String detail = expensesUrl + "/" + original.id();
    var legacy = objectMapper.readValue(read(detail), ExpenseResponse.class);
    assertThat(legacy.splitType()).isEqualTo(SplitType.UNEQUAL);
    assertThat(legacy.splits())
        .containsExactlyInAnyOrder(
            new SplitInput(ownerId, 1), new SplitInput(secondId, 0), new SplitInput(thirdId, 0));
    var edit =
        new CreateExpenseRequest(
            "Legacy description changed", 1, ownerId, null, legacy.splitType(), legacy.splits());
    var changed =
        expense(
            mockMvc
                .perform(
                    put(detail)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(edit)))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(changed.splits()).containsExactlyInAnyOrderElementsOf(legacy.splits());
    assertThat(changed.participants()).containsExactlyInAnyOrderElementsOf(legacy.participants());
  }

  @org.junit.jupiter.api.Test
  void equalSubsetAndExactInputsRemainOriginalAfterDescriptionEdit() throws Exception {
    for (var input :
        List.of(
            new CreateExpenseRequest("Subset", 1, ownerId, List.of(ownerId, secondId)),
            request(SplitType.UNEQUAL, 1, 1, 0, 0))) {
      var original =
          expense(
              mockMvc
                  .perform(
                      jsonPost(expensesUrl, input).header(HttpHeaders.AUTHORIZATION, authorization))
                  .andExpect(status().isCreated())
                  .andReturn());
      String detail = expensesUrl + "/" + original.id();
      var saved = objectMapper.readValue(read(detail), ExpenseResponse.class);
      if (saved.splitType() == SplitType.EQUAL) {
        assertThat(saved.participantUserIds()).containsExactlyInAnyOrder(ownerId, secondId);
        assertThat(saved.participants()).hasSize(2);
      } else {
        assertThat(saved.splits()).containsExactlyInAnyOrderElementsOf(input.splits());
      }
      var edit =
          new CreateExpenseRequest(
              "New description",
              saved.amountCents(),
              ownerId,
              saved.participantUserIds(),
              saved.splitType(),
              saved.splits());
      var changed =
          expense(
              mockMvc
                  .perform(
                      put(detail)
                          .header(HttpHeaders.AUTHORIZATION, authorization)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(json(edit)))
                  .andExpect(status().isOk())
                  .andReturn());
      assertThat(changed.participants()).containsExactlyInAnyOrderElementsOf(saved.participants());
      assertThat(changed.splits()).isEqualTo(saved.splits());
      assertThat(changed.participantUserIds()).isEqualTo(saved.participantUserIds());
    }
  }

  @ParameterizedTest
  @CsvSource({"WEIGHTS,POST", "WEIGHTS,PUT", "ADJUSTMENTS,POST", "ADJUSTMENTS,PUT"})
  void invalidNewModesRejectAtomically(SplitType type, String method) throws Exception {
    var original =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, request(SplitType.PERCENTAGE, 1, 5000, 5000, 0))
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    String detail = expensesUrl + "/" + original.id();
    String before = read(detail), listBefore = read(expensesUrl);
    String balancesUrl = expensesUrl.replace("/expenses", "/balances"),
        balancesBefore = read(balancesUrl);
    List<long[]> cases =
        type == SplitType.WEIGHTS
            ? List.of(
                new long[] {0, 1, 1},
                new long[] {-1, 1, 1},
                new long[] {1_000_000_000_001L, 1, 1},
                new long[] {Long.MAX_VALUE, Long.MAX_VALUE, 2})
            : List.of(
                new long[] {1, 0, 0},
                new long[] {-4, 4, 0},
                new long[] {-1_000_000_000_001L, 1_000_000_000_001L, 0},
                new long[] {Long.MIN_VALUE, Long.MIN_VALUE, 0});
    for (long[] values : cases) {
      var input = request(type, 5, values[0], values[1], values[2]);
      var req =
          method.equals("POST")
              ? jsonPost(expensesUrl, input)
              : put(detail).contentType(MediaType.APPLICATION_JSON).content(json(input));
      mockMvc
          .perform(req.header(HttpHeaders.AUTHORIZATION, authorization))
          .andExpect(status().isBadRequest());
      assertThat(read(detail)).isEqualTo(before);
      assertThat(read(expensesUrl)).isEqualTo(listBefore);
      assertThat(read(balancesUrl)).isEqualTo(balancesBefore);
    }
  }

  @org.junit.jupiter.api.Test
  void maximumWeightsAreOverflowSafeAndExcludedPayerUsesFirstUuidForRemainder() throws Exception {
    var cap =
        new CreateExpenseRequest(
            "Large weights",
            999_999_999_999L,
            ownerId,
            null,
            SplitType.WEIGHTS,
            List.of(
                new SplitInput(ownerId, 1_000_000_000_000L),
                new SplitInput(secondId, 1_000_000_000_000L)));
    var created =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, cap).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    assertThat(created.participants())
        .hasSize(2)
        .allSatisfy(
            p ->
                assertThat(p.shareCents())
                    .isEqualTo(
                        p.user().id().equals(ownerId) ? 500_000_000_000L : 499_999_999_999L));
    UUID first = secondId.compareTo(thirdId) < 0 ? secondId : thirdId;
    for (SplitType type : List.of(SplitType.WEIGHTS, SplitType.ADJUSTMENTS)) {
      var input =
          new CreateExpenseRequest(
              "Payer excluded",
              1,
              ownerId,
              null,
              type,
              List.of(
                  new SplitInput(secondId, type == SplitType.WEIGHTS ? 1 : 0),
                  new SplitInput(thirdId, type == SplitType.WEIGHTS ? 1 : 0)));
      var tiny =
          expense(
              mockMvc
                  .perform(
                      jsonPost(expensesUrl, input).header(HttpHeaders.AUTHORIZATION, authorization))
                  .andExpect(status().isCreated())
                  .andReturn());
      assertThat(tiny.participants())
          .hasSize(2)
          .allSatisfy(
              p -> assertThat(p.shareCents()).isEqualTo(p.user().id().equals(first) ? 1 : 0));
    }
  }

  @org.junit.jupiter.api.Test
  void zeroShareHistoryStillBlocksEditingAndOutsidersSee403Before404() throws Exception {
    var input = request(SplitType.PERCENTAGE, 1, 10000, 0, 0);
    var original =
        expense(
            mockMvc
                .perform(
                    jsonPost(expensesUrl, input).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isCreated())
                .andReturn());
    String detail = expensesUrl + "/" + original.id();
    String before = read(detail);
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    expensesUrl.replace("/expenses", "/members/") + thirdId)
                .header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            put(detail)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(input)))
        .andExpect(status().isConflict());
    assertThat(read(detail)).isEqualTo(before);
    var outsider =
        register("outside-" + UUID.randomUUID() + "@example.com", "password123", "Outside");
    for (String url :
        List.of(
            detail,
            expensesUrl + "/" + UUID.randomUUID(),
            "/api/groups/" + UUID.randomUUID() + "/expenses/" + UUID.randomUUID())) {
      mockMvc
          .perform(
              put(url)
                  .header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(input)))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(outsider.accessToken())))
          .andExpect(status().isForbidden());
    }
  }
}
