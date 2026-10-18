package com.spliteasy.controller;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.LoginRequest;
import com.spliteasy.dto.auth.RegisterRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;

@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

  @Test
  void registerReturnsCreatedWithTokenAndUser() throws Exception {
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/register",
                new RegisterRequest("alice@example.com", "password123", "Alice")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").exists())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn", is(greaterThan(0))))
        .andExpect(jsonPath("$.user.email").value("alice@example.com"))
        .andExpect(jsonPath("$.user.displayName").value("Alice"))
        // password/hash must never be serialized
        .andExpect(jsonPath("$.user.password").doesNotExist())
        .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
  }

  @Test
  void registerDuplicateEmailReturnsConflict() throws Exception {
    register("dupe@example.com", "password123", "First");
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/register",
                new RegisterRequest("dupe@example.com", "password123", "Second")))
        .andExpect(status().isConflict());
  }

  @Test
  void registerInvalidPayloadReturnsBadRequest() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/register", new RegisterRequest("not-an-email", "short", "")))
        .andExpect(status().isBadRequest());
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "é", "😀"})
  void registerAccepts72BytePasswordsWithoutTransformingThem(String character) throws Exception {
    String password = character.repeat(72 / character.getBytes(StandardCharsets.UTF_8).length);
    String email = "boundary-" + character.codePointAt(0) + "@example.com";
    mockMvc
        .perform(jsonPost("/api/auth/register", new RegisterRequest(email, password, "Boundary")))
        .andExpect(status().isCreated());
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest(email, password)))
        .andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "é", "😀"})
  void registerRejectsPasswordsAbove72Utf8Bytes(String character) throws Exception {
    String password = character.repeat(72 / character.getBytes(StandardCharsets.UTF_8).length + 1);
    mockMvc
        .perform(
            jsonPost(
                "/api/auth/register",
                new RegisterRequest("over-limit@example.com", password, "Over limit")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Password must be at most 72 UTF-8 bytes"));
  }

  @Test
  void loginWithValidCredentialsReturnsToken() throws Exception {
    register("bob@example.com", "password123", "Bob");
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest("bob@example.com", "password123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists())
        .andExpect(jsonPath("$.user.email").value("bob@example.com"));
  }

  @Test
  void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
    register("carol@example.com", "password123", "Carol");
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest("carol@example.com", "wrongpass")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void loginWithUnknownEmailReturnsUnauthorized() throws Exception {
    mockMvc
        .perform(jsonPost("/api/auth/login", new LoginRequest("nobody@example.com", "password123")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointRejectsMissingToken() throws Exception {
    mockMvc.perform(get("/api/groups")).andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointAcceptsValidToken() throws Exception {
    AuthResponse auth = register("dave@example.com", "password123", "Dave");
    mockMvc
        .perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer(auth.accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void protectedEndpointRejectsGarbageToken() throws Exception {
    mockMvc
        .perform(get("/api/groups").header(HttpHeaders.AUTHORIZATION, bearer("not.a.jwt")))
        .andExpect(status().isUnauthorized());
  }
}
