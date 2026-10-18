package com.spliteasy.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.LoginRequest;
import com.spliteasy.dto.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@AutoConfigureMockMvc
class AuthAbuseIntegrationTest extends AbstractIntegrationTest {
  @Test
  void invalidCredentialsBecomeGeneric429AndSpoofedForwardedHeadersCannotBypassIt()
      throws Exception {
    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              jsonPost("/api/auth/login", new LoginRequest("limit-unknown@example.com", "wrong"))
                  .with(
                      r -> {
                        r.setRemoteAddr("198.51.100.62");
                        return r;
                      }))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(
            jsonPost("/api/auth/login", new LoginRequest("LIMIT-UNKNOWN@example.com", "wrong"))
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.62");
                      return r;
                    })
                .header("X-Forwarded-For", "203.0.113.1")
                .header("CF-Connecting-IP", "203.0.113.2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]{0,2}")))
        .andExpect(
            jsonPath("$.message")
                .value("Too many authentication attempts. Please try again later."))
        .andExpect(jsonPath("$.status").value(429));
    mockMvc
        .perform(
            jsonPost("/api/auth/login", new LoginRequest("limit-unknown@example.com", "wrong"))
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.63");
                      return r;
                    }))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void registrationLimitCountsSuccessAndConflictWithoutBlockingLoginOrOtherClients()
      throws Exception {
    var request = new RegisterRequest("limited-signup@example.com", "password123", "Limited");
    for (int i = 0; i < 20; i++) {
      mockMvc
          .perform(
              jsonPost("/api/auth/register", request)
                  .with(
                      r -> {
                        r.setRemoteAddr("198.51.100.64");
                        return r;
                      }))
          .andExpect(i == 0 ? status().isCreated() : status().isConflict());
    }
    mockMvc
        .perform(
            jsonPost("/api/auth/register", request)
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.64");
                      return r;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    mockMvc
        .perform(
            jsonPost("/api/auth/login", new LoginRequest(request.email(), request.password()))
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.64");
                      return r;
                    }))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isString());
    mockMvc
        .perform(
            jsonPost(
                    "/api/auth/register",
                    new RegisterRequest("other-signup@example.com", "password123", "Other"))
                .with(
                    r -> {
                      r.setRemoteAddr("198.51.100.65");
                      return r;
                    }))
        .andExpect(status().isCreated());
  }
}
