package com.spliteasy.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.RegisterRequest;
import com.spliteasy.dto.group.AddMemberRequest;
import com.spliteasy.dto.group.CreateGroupRequest;
import com.spliteasy.dto.group.GroupResponse;
import com.spliteasy.exception.GlobalExceptionHandler;
import com.spliteasy.repository.GroupMembershipRepository;
import com.spliteasy.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Concurrent MVC requests and PostgreSQL transactions; spies only pause real prechecks. */
@AutoConfigureMockMvc
class UniqueConflictIntegrationTest extends AbstractIntegrationTest {

  @MockitoSpyBean UserRepository userRepository;
  @MockitoSpyBean GroupMembershipRepository membershipRepository;
  @MockitoSpyBean GlobalExceptionHandler exceptionHandler;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void observeConflictMappingAfterTransactionCleanup() {
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return invocation.callRealMethod();
            })
        .when(exceptionHandler)
        .handleDataIntegrity(any(DataIntegrityViolationException.class));
  }

  @Test
  void racedRegistrationsPersistOneUserAndReturnCreatedAndConflict() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    doAnswer(afterAbsentPrechecks(new CyclicBarrier(2))).when(userRepository).existsByEmail(email);
    RegisterRequest request = new RegisterRequest(email, "password123", "Racing user");

    List<MvcResult> results =
        race(() -> mockMvc.perform(jsonPost("/api/auth/register", request)).andReturn());

    assertConflict(results, 201, "Email is already registered");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from users where email = ?", Long.class, email))
        .isEqualTo(1L);
  }

  @Test
  void racedMemberAddsPersistOneMembershipAndReturnOkAndConflict() throws Exception {
    String id = UUID.randomUUID().toString();
    AuthResponse owner = register(id + "-owner@example.com", "password123", "Owner");
    AuthResponse invitee = register(id + "-invitee@example.com", "password123", "Invitee");
    MvcResult created =
        mockMvc
            .perform(
                jsonPost("/api/groups", new CreateGroupRequest("Race"))
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
            .andReturn();
    UUID groupId =
        objectMapper
            .readValue(created.getResponse().getContentAsString(), GroupResponse.class)
            .id();
    doAnswer(afterAbsentPrechecks(new CyclicBarrier(2)))
        .when(membershipRepository)
        .existsByGroupIdAndUserId(groupId, invitee.user().id());

    List<MvcResult> results =
        race(
            () ->
                mockMvc
                    .perform(
                        jsonPost(
                                "/api/groups/" + groupId + "/members",
                                new AddMemberRequest(invitee.user().email()))
                            .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken())))
                    .andReturn());

    assertConflict(results, 200, "User is already a member of this group");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from group_memberships where group_id = ? and user_id = ?",
                Long.class,
                groupId,
                invitee.user().id()))
        .isEqualTo(1L);
    assertThat(membershipRepository.countByGroupId(groupId)).isEqualTo(2L);
  }

  private Answer<Object> afterAbsentPrechecks(CyclicBarrier barrier) {
    return invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      // JDK repository proxies delegate through the spy's default answer, rather than
      // callRealMethod.
      Object exists =
          mockingDetails(invocation.getMock())
              .getMockCreationSettings()
              .getDefaultAnswer()
              .answer(invocation);
      assertThat(exists).isEqualTo(false);
      barrier.await(20, TimeUnit.SECONDS);
      return exists;
    };
  }

  private List<MvcResult> race(Callable<MvcResult> request) throws Exception {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(request);
      var second = executor.submit(request);
      return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
    }
  }

  private void assertConflict(List<MvcResult> results, int success, String message)
      throws Exception {
    assertThat(results)
        .extracting(result -> result.getResponse().getStatus())
        .containsExactlyInAnyOrder(success, 409);
    MvcResult conflict =
        results.stream()
            .filter(result -> result.getResponse().getStatus() == 409)
            .findFirst()
            .orElseThrow();
    assertThat(conflict.getResolvedException()).isInstanceOf(DataIntegrityViolationException.class);
    verify(exceptionHandler).handleDataIntegrity(any(DataIntegrityViolationException.class));
    var body = objectMapper.readTree(conflict.getResponse().getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(409);
    assertThat(body.get("error").asText()).isEqualTo("Conflict");
    assertThat(body.get("message").asText()).isEqualTo(message);
  }
}
