package com.spliteasy.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.LoginRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@AutoConfigureMockMvc
class AccountRecoveryIntegrationTest extends AbstractIntegrationTest {
  @Test
  void unavailableDeliveryNeverPretendsToSendOrRevealsAccountExistence() throws Exception {
    register("recovery-disabled@example.com", "password123", "Recovery");
    for (String email : new String[] {"recovery-disabled@example.com", "unknown@example.com"}) {
      mockMvc
          .perform(jsonPost("/api/auth/password-reset-requests", Map.of("email", email)))
          .andExpect(status().isServiceUnavailable())
          .andExpect(
              jsonPath("$.message")
                  .value("Email delivery is currently unavailable. Try again later."))
          .andExpect(jsonPath("$.token").doesNotExist());
    }
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/login",
                new LoginRequest("recovery-disabled@example.com", "password123")))
        .andExpect(status().isOk());
  }
}
