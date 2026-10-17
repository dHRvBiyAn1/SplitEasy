package com.spliteasy.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  @ParameterizedTest
  @MethodSource("conflicts")
  void optimisticConflictsHaveAnActionable409(RuntimeException conflict) throws Exception {
    MockMvcBuilders.standaloneSetup(new FailingController(conflict))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build()
        .perform(get("/failure"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "This record was changed by another request. Reload and review the latest changes before saving again."))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  static Stream<RuntimeException> conflicts() {
    return Stream.of(
        new OptimisticLockingFailureException("internal persistence details"),
        new ObjectOptimisticLockingFailureException("ExpenseParticipant", "internal-id"));
  }

  @RestController
  record FailingController(RuntimeException failure) {
    @GetMapping("/failure")
    void fail() {
      throw failure;
    }
  }
}
