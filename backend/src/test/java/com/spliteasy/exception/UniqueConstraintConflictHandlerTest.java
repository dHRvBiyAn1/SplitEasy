package com.spliteasy.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class UniqueConstraintConflictHandlerTest {

  @ParameterizedTest
  @CsvSource({
    "users_email_key,Email is already registered",
    "uq_group_memberships_group_user,User is already a member of this group"
  })
  void knownUniqueConstraintsReturnFriendlyConflicts(String constraint, String message)
      throws Exception {
    mvc(integrity("23505", constraint))
        .perform(get("/failure"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(message));
  }

  @ParameterizedTest
  @CsvSource({
    "23505,expense_participants_expense_id_user_id_key",
    "23503,users_email_key",
    "23514,uq_group_memberships_group_user",
    ",users_email_key",
    "23505,"
  })
  void unrelatedIntegrityFailuresRemainServerErrors(String sqlState, String constraint)
      throws Exception {
    mvc(integrity(sqlState, constraint))
        .perform(get("/failure"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"users_email_key", "uq_group_memberships_group_user"})
  void constraintNamesInPlainMessagesDoNotImplyConflict(String message) throws Exception {
    mvc(new DataIntegrityViolationException(message))
        .perform(get("/failure"))
        .andExpect(status().isInternalServerError());
  }

  private MockMvc mvc(RuntimeException failure) {
    return MockMvcBuilders.standaloneSetup(new FailureController(failure))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  private DataIntegrityViolationException integrity(String sqlState, String constraint) {
    return new DataIntegrityViolationException(
        "not client safe",
        new IllegalStateException(
            new ConstraintViolationException(
                "not client safe", new SQLException("not client safe", sqlState), constraint)));
  }

  @RestController
  static class FailureController {
    private final RuntimeException failure;

    FailureController(RuntimeException failure) {
      this.failure = failure;
    }

    @GetMapping("/failure")
    void fail() {
      throw failure;
    }
  }
}
