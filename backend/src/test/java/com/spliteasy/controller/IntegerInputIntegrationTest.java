package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfigureMockMvc
class IntegerInputIntegrationTest extends AbstractIntegrationTest {
  @Autowired private JdbcTemplate jdbc;

  private String authorization;
  private String groupUrl;
  private UUID ownerId;
  private UUID memberId;

  @BeforeEach
  void createGroup() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var owner = register("integer-owner-" + suffix + "@example.com", "password123", "Owner");
    var member = register("integer-member-" + suffix + "@example.com", "password123", "Member");
    authorization = bearer(owner.accessToken());
    ownerId = owner.user().id();
    memberId = member.user().id();
    var result =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Integer inputs"))
                    .header(HttpHeaders.AUTHORIZATION, authorization))
            .andExpect(status().isCreated())
            .andReturn();
    groupUrl =
        "/api/groups/"
            + objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    mockMvc
        .perform(
            jsonPost(groupUrl + "/members", new AddMemberRequest(member.user().email()))
                .header(HttpHeaders.AUTHORIZATION, authorization))
        .andExpect(status().isOk());
  }

  private String expense(String amount, String type, String first, String second) {
    return """
        {"description":"Replacement","amountCents":%s,"paidByUserId":"%s","splitType":"%s",
         "splits":[{"userId":"%s","value":%s},{"userId":"%s","value":%s}]}
        """
        .formatted(amount, ownerId, type, ownerId, first, memberId, second);
  }

  private String send(String method, String url, String body, int expected) throws Exception {
    return mockMvc
        .perform(
            request(HttpMethod.valueOf(method), url)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().is(expected))
        .andReturn()
        .getResponse()
        .getContentAsString();
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
  @CsvSource({
    "POST,EQUAL,1000.99,0,0", "PUT,EQUAL,1000.99,0,0",
    "POST,UNEQUAL,1000,500.99,500.99", "PUT,UNEQUAL,1000,500.99,500.99",
    "POST,PERCENTAGE,1000,5000.99,5000.99", "PUT,PERCENTAGE,1000,5000.99,5000.99",
    "POST,UNEQUAL,1000,-0.9,1000", "PUT,PERCENTAGE,1000,-0.9,10000"
  })
  void fractionalExpenseInputsRejectWithoutChangingStoredExpenseOrBalances(
      String method, String type, String amount, String first, String second) throws Exception {
    String expensesUrl = groupUrl + "/expenses";
    String original = send("POST", expensesUrl, expense("900", "UNEQUAL", "300", "600"), 201);
    String detailUrl = expensesUrl + "/" + objectMapper.readTree(original).get("id").asText();
    UUID expenseId = UUID.fromString(objectMapper.readTree(original).get("id").asText());
    var participantIdsBefore =
        jdbc.queryForList(
            "select id from expense_participants where expense_id = ? order by id",
            UUID.class,
            expenseId);
    String listBefore = read(expensesUrl);
    String detailBefore = read(detailUrl);
    String balancesBefore = read(groupUrl + "/balances");
    send(
        method,
        method.equals("POST") ? expensesUrl : detailUrl,
        expense(amount, type, first, second),
        400);
    assertThat(
            jdbc.queryForList(
                "select id from expense_participants where expense_id = ? order by id",
                UUID.class,
                expenseId))
        .isEqualTo(participantIdsBefore);
    assertThat(read(expensesUrl)).isEqualTo(listBefore);
    assertThat(read(detailUrl)).isEqualTo(detailBefore);
    assertThat(read(groupUrl + "/balances")).isEqualTo(balancesBefore);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1000.99", "0.99"})
  void fractionalPaymentAmountRejectsWithoutCreatingPayment(String amount) throws Exception {
    String url = groupUrl + "/payments";
    String before = read(url);
    String balancesBefore = read(groupUrl + "/balances");
    send("POST", url, payment(amount), 400);
    assertThat(read(url)).isEqualTo(before);
    assertThat(read(groupUrl + "/balances")).isEqualTo(balancesBefore);
  }

  private String payment(String amount) {
    return """
        {"payerUserId":"%s","payeeUserId":"%s","amountCents":%s}
        """
        .formatted(ownerId, memberId, amount);
  }

  @ParameterizedTest
  @CsvSource({"EQUAL,0,0", "UNEQUAL,300,700", "PERCENTAGE,3000,7000"})
  void integerInputsStillCreateAndReplaceExpensesAndRecordPayments(
      String type, String first, String second) throws Exception {
    String expensesUrl = groupUrl + "/expenses";
    String body = expense("1000", type, first, second);
    String created = send("POST", expensesUrl, body, 201);
    String detailUrl = expensesUrl + "/" + objectMapper.readTree(created).get("id").asText();
    String replaced = send("PUT", detailUrl, body, 200);
    assertThat(objectMapper.readTree(replaced).get("amountCents").asLong()).isEqualTo(1000);
    assertThat(read(detailUrl)).isEqualTo(replaced);
    String recorded = send("POST", groupUrl + "/payments", payment("1000"), 201);
    assertThat(objectMapper.readTree(recorded).get("amountCents").asLong()).isEqualTo(1000);
  }
}
