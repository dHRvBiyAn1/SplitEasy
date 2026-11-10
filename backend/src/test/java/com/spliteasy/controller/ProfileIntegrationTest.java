package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.spliteasy.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

@AutoConfigureMockMvc
class ProfileIntegrationTest extends AbstractIntegrationTest {
  @Test
  void profilePersistsAcrossFreshLoginAndKeepsRegistrationDate() throws Exception {
    var auth = register("profile-persist@example.com", "password123", "Alice");
    var before =
        mockMvc
            .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredCurrency").value("USD"))
            .andExpect(jsonPath("$.avatarColor").value("#12A57E"))
            .andReturn();
    String createdAt =
        objectMapper.readTree(before.getResponse().getContentAsString()).get("createdAt").asText();
    mockMvc
        .perform(
            patch("/api/me")
                .header("Authorization", bearer(auth.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "displayName",
                            "Alice Updated",
                            "phone",
                            "+91 123",
                            "preferredCurrency",
                            "INR",
                            "avatarColor",
                            "#3F7BD9"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Alice Updated"));
    var fresh = login("profile-persist@example.com", "password123");
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(auth.user().id().toString()))
        .andExpect(jsonPath("$.displayName").value("Alice Updated"))
        .andExpect(jsonPath("$.phone").value("+91 123"))
        .andExpect(jsonPath("$.preferredCurrency").value("INR"))
        .andExpect(jsonPath("$.avatarColor").value("#3F7BD9"))
        .andExpect(jsonPath("$.createdAt").value(createdAt))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
  }

  @Test
  void missingAndInvalidTokensCannotReadOrPatchProfiles() throws Exception {
    for (String token : new String[] {"", "Bearer invalid.jwt.token"}) {
      mockMvc
          .perform(get("/api/me").header("Authorization", token))
          .andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              patch("/api/me")
                  .header("Authorization", token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"displayName\":\"Intruder\"}"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void payloadCannotTargetAnotherAccountOrChangeEmail() throws Exception {
    var alice = register("profile-owner@example.com", "password123", "Alice");
    var bob = register("profile-other@example.com", "password123", "Bob");
    mockMvc
        .perform(
            patch("/api/me")
                .header("Authorization", bearer(alice.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json(
                        Map.of(
                            "id",
                            bob.user().id(),
                            "userId",
                            bob.user().id(),
                            "email",
                            "changed@example.com",
                            "displayName",
                            "Alice Updated"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(alice.user().id().toString()))
        .andExpect(jsonPath("$.email").value("profile-owner@example.com"));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(bob.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Bob"));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(alice.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Alice Updated"));
  }

  @Test
  void invalidPreferencesRejectWholePatchAndNullLeavesFieldsUnchanged() throws Exception {
    var alice = register("profile-invalid@example.com", "password123", "Alice");
    for (var invalid :
        java.util.List.of(
            Map.of("preferredCurrency", "CAD"),
            Map.of("avatarColor", "url(evil)"),
            Map.of("phone", "x".repeat(51)),
            Map.of("displayName", "   "),
            Map.of("displayName", "x".repeat(101)))) {
      var patch = new java.util.HashMap<String, String>();
      patch.put("displayName", "Must not persist");
      patch.putAll(invalid);
      mockMvc
          .perform(
              patch("/api/me")
                  .header("Authorization", bearer(alice.accessToken()))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(patch)))
          .andExpect(status().isBadRequest());
      mockMvc
          .perform(get("/api/me").header("Authorization", bearer(alice.accessToken())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.displayName").value("Alice"))
          .andExpect(jsonPath("$.phone").value(""))
          .andExpect(jsonPath("$.preferredCurrency").value("USD"));
    }
    mockMvc
        .perform(
            patch("/api/me")
                .header("Authorization", bearer(alice.accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":null,\"phone\":null}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Alice"));
  }
}
