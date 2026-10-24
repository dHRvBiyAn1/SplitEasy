package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.spliteasy.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@AutoConfigureMockMvc
class SessionLifecycleIntegrationTest extends AbstractIntegrationTest {
  @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"logout", "revoke", "logout-all"})
  void explicitRevocationStopsBothCopiedAccessAndCookieRenewal(String action) throws Exception {
    var registration =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/register",
                    new com.spliteasy.dto.auth.RegisterRequest(
                        UUID.randomUUID() + "@example.com", "password123", "Revocation owner")))
            .andExpect(status().isCreated())
            .andReturn();
    var auth =
        objectMapper.readValue(
            registration.getResponse().getContentAsString(),
            com.spliteasy.dto.auth.AuthResponse.class);
    var command =
        action.equals("revoke")
            ? delete("/api/me/sessions/" + auth.sessionId())
            : jsonPost(
                action.equals("logout") ? "/api/auth/logout" : "/api/me/sessions/logout-all",
                java.util.Map.of());
    mockMvc
        .perform(command.header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", java.util.Map.of())
                .cookie(registration.getResponse().getCookie("__Host-spliteasy-refresh"))
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isUnauthorized());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"expires_at", "idle_expires_at", "rotations"})
  void serverExpiryAndExhaustedRotationBudgetsCannotBeRenewed(String boundary) throws Exception {
    var registration =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/register",
                    new com.spliteasy.dto.auth.RegisterRequest(
                        UUID.randomUUID() + "@example.com", "password123", "Expiry owner")))
            .andExpect(status().isCreated())
            .andReturn();
    var auth =
        objectMapper.readValue(
            registration.getResponse().getContentAsString(),
            com.spliteasy.dto.auth.AuthResponse.class);
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isOk());
    // Fixtures place the persisted server boundary in the past; outcomes remain HTTP observations.
    if (boundary.equals("rotations"))
      jdbc.update("update auth_sessions set rotations=2048 where id=?", auth.sessionId());
    else
      jdbc.update(
          "update auth_sessions set "
              + boundary
              + "=CURRENT_TIMESTAMP - interval '1 second' where id=?",
          auth.sessionId());
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", java.util.Map.of())
                .cookie(registration.getResponse().getCookie("__Host-spliteasy-refresh"))
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(auth.accessToken())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void duplicateConcurrentRefreshesHaveOneWinnerAndRevokeItsFamily() throws Exception {
    var registered =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/register",
                    new com.spliteasy.dto.auth.RegisterRequest(
                        UUID.randomUUID() + "@example.com", "password123", "Race owner")))
            .andExpect(status().isCreated())
            .andReturn();
    var auth =
        objectMapper.readValue(
            registered.getResponse().getContentAsString(),
            com.spliteasy.dto.auth.AuthResponse.class);
    var cookie = registered.getResponse().getCookie("__Host-spliteasy-refresh");
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var lock = connection.prepareStatement("select id from users where id=? for update")) {
        lock.setObject(1, auth.user().id());
        lock.executeQuery().close();
      }
      java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> renewal =
          () ->
              mockMvc
                  .perform(
                      jsonPost("/api/auth/refresh", java.util.Map.of())
                          .cookie(cookie)
                          .header("Origin", "http://localhost:4200")
                          .header("X-SplitEasy-Session", "1"))
                  .andReturn();
      var first = workers.submit(renewal);
      var second = workers.submit(renewal);
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
      while (jdbc.queryForObject(
              "select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%users%'",
              Integer.class)
          < 2) {
        org.junit.jupiter.api.Assertions.assertTrue(
            System.nanoTime() < deadline, "Both HTTP renewals must reach the account lock");
        Thread.sleep(10);
      }
      connection.commit();
      var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      org.junit.jupiter.api.Assertions.assertEquals(
          java.util.List.of(200, 401),
          java.util.stream.Stream.of(a, b)
              .map(result -> result.getResponse().getStatus())
              .sorted()
              .toList());
      var winner = a.getResponse().getStatus() == 200 ? a : b;
      String copied =
          objectMapper
              .readTree(winner.getResponse().getContentAsString())
              .get("accessToken")
              .asText();
      mockMvc
          .perform(get("/api/me").header("Authorization", bearer(copied)))
          .andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              jsonPost("/api/auth/refresh", java.util.Map.of())
                  .cookie(winner.getResponse().getCookie("__Host-spliteasy-refresh"))
                  .header("Origin", "http://localhost:4200")
                  .header("X-SplitEasy-Session", "1"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void reuseOfAnyConsumedRefreshCredentialRevokesTheEntireFamilyAfterCommit() throws Exception {
    var registered =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/register",
                    new com.spliteasy.dto.auth.RegisterRequest(
                        UUID.randomUUID() + "@example.com", "password123", "Replay owner")))
            .andExpect(status().isCreated())
            .andReturn();
    var original = registered.getResponse().getCookie("__Host-spliteasy-refresh");
    var first =
        mockMvc
            .perform(
                jsonPost("/api/auth/refresh", java.util.Map.of())
                    .cookie(original)
                    .header("Origin", "http://localhost:4200")
                    .header("X-SplitEasy-Session", "1"))
            .andExpect(status().isOk())
            .andReturn();
    var second =
        mockMvc
            .perform(
                jsonPost("/api/auth/refresh", java.util.Map.of())
                    .cookie(first.getResponse().getCookie("__Host-spliteasy-refresh"))
                    .header("Origin", "http://localhost:4200")
                    .header("X-SplitEasy-Session", "1"))
            .andExpect(status().isOk())
            .andReturn();
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", java.util.Map.of())
                .cookie(original)
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
    String copied =
        objectMapper
            .readTree(second.getResponse().getContentAsString())
            .get("accessToken")
            .asText();
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(copied)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", java.util.Map.of())
                .cookie(second.getResponse().getCookie("__Host-spliteasy-refresh"))
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void refreshBudgetIsBoundedWithoutConsumingExistingLoginOrAccessBudgets() throws Exception {
    var owner = register(UUID.randomUUID() + "@example.com", "password123", "Refresh budget");
    for (int attempt = 0; attempt < 120; attempt++) {
      mockMvc
          .perform(
              jsonPost("/api/auth/refresh", java.util.Map.of())
                  .header("Origin", "http://localhost:4200")
                  .header("X-SplitEasy-Session", "1"))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(
            jsonPost("/api/auth/refresh", java.util.Map.of())
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .exists("Retry-After"));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(owner.accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void newLoginsKeepAtMostTwentyActiveDevicesAndEvictTheOldest() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var oldest = register(email, "password123", "Many devices");
    com.spliteasy.dto.auth.AuthResponse newest = oldest;
    for (int n = 0; n < 21; n++) {
      int client = n;
      var result =
          mockMvc
              .perform(
                  jsonPost(
                          "/api/auth/login",
                          new com.spliteasy.dto.auth.LoginRequest(email, "password123"))
                      .with(
                          request -> {
                            request.setRemoteAddr("198.19.1." + client);
                            return request;
                          }))
              .andExpect(status().isOk())
              .andReturn();
      newest =
          objectMapper.readValue(
              result.getResponse().getContentAsString(), com.spliteasy.dto.auth.AuthResponse.class);
    }
    mockMvc
        .perform(get("/api/me/sessions").header("Authorization", bearer(newest.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(20));
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(oldest.accessToken())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void passwordChangeRevokesEveryDeviceAndItsRefreshCredential() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var first = register(email, "password123", "Credential owner");
    var login =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/login",
                    new com.spliteasy.dto.auth.LoginRequest(email, "password123")))
            .andExpect(status().isOk())
            .andReturn();
    var second =
        objectMapper.readValue(
            login.getResponse().getContentAsString(), com.spliteasy.dto.auth.AuthResponse.class);
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    java.util.Map.of(
                        "currentPassword", "password123", "password", "new-password123"))
                .header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/auth/refresh")
                .cookie(login.getResponse().getCookie("__Host-spliteasy-refresh"))
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isUnauthorized());
    var fresh = login(email, "new-password123");
    mockMvc
        .perform(get("/api/me/sessions").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void cookieRefreshRequiresTrustedOriginAndExplicitBrowserHeader() throws Exception {
    var registered =
        mockMvc
            .perform(
                jsonPost(
                    "/api/auth/register",
                    new com.spliteasy.dto.auth.RegisterRequest(
                        UUID.randomUUID() + "@example.com", "password123", "CSRF owner")))
            .andExpect(status().isCreated())
            .andReturn();
    var cookie = registered.getResponse().getCookie("__Host-spliteasy-refresh");
    for (String origin : new String[] {"", "null", "https://untrusted.example"}) {
      var request =
          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                  "/api/auth/refresh")
              .cookie(cookie)
              .header("X-SplitEasy-Session", "1");
      if (!origin.isEmpty()) request.header("Origin", origin);
      mockMvc.perform(request).andExpect(status().isForbidden());
    }
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/auth/refresh")
                .cookie(cookie)
                .header("Origin", "http://localhost:4200"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/api/auth/refresh")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-SplitEasy-Session,X-Ledger-Version"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("Access-Control-Allow-Credentials", "true"));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/auth/refresh")
                .cookie(cookie)
                .header("Origin", "http://localhost:4200")
                .header("X-SplitEasy-Session", "1"))
        .andExpect(status().isOk());
  }

  @Test
  void browserLoginSetsOnlyASecureHttpOnlyRefreshCookieAndRenewalKeepsTheDevice() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    register(email, "password123", "Browser owner");
    var signedIn =
        mockMvc
            .perform(
                jsonPost(
                        "/api/auth/login",
                        new com.spliteasy.dto.auth.LoginRequest(email, "password123"))
                    .header("Origin", "http://localhost:4200"))
            .andExpect(status().isOk())
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                    .string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")))
            .andReturn();
    var cookie = signedIn.getResponse().getCookie("__Host-spliteasy-refresh");
    org.junit.jupiter.api.Assertions.assertNotNull(cookie);
    org.junit.jupiter.api.Assertions.assertEquals("/", cookie.getPath());
    org.junit.jupiter.api.Assertions.assertNull(cookie.getDomain());
    var original = objectMapper.readTree(signedIn.getResponse().getContentAsString());
    org.junit.jupiter.api.Assertions.assertFalse(original.has("refreshToken"));
    var renewed =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/auth/refresh")
                    .cookie(cookie)
                    .header("Origin", "http://localhost:4200")
                    .header("X-SplitEasy-Session", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionId").value(original.get("sessionId").asText()))
            .andExpect(jsonPath("$.expiresIn").value(3600))
            .andReturn();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        cookie.getValue(), renewed.getResponse().getCookie("__Host-spliteasy-refresh").getValue());
    var access =
        objectMapper
            .readTree(renewed.getResponse().getContentAsString())
            .get("accessToken")
            .asText();
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(access)))
        .andExpect(status().isOk());
  }

  @Test
  void capturedBearerLogoutCannotRevokeAReplacementLoginOrClearItsCookie() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var first = register(email, "password123", "Owner");
    mockMvc
        .perform(
            jsonPost("/api/auth/logout", java.util.Map.of())
                .header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isNoContent())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .doesNotExist("Set-Cookie"));
    var replacement = login(email, "password123");
    mockMvc
        .perform(
            jsonPost("/api/auth/logout", java.util.Map.of())
                .header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(jsonPost("/api/auth/logout", java.util.Map.of()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(replacement.accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void logoutAllRevokesOnlyTheOwnerAndForeignRevocationNeverLeaksSessions() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var first = register(email, "password123", "Owner");
    var second = login(email, "password123");
    var stranger = register(UUID.randomUUID() + "@example.com", "password123", "Stranger");
    mockMvc
        .perform(
            delete("/api/me/sessions/" + first.sessionId())
                .header("Authorization", bearer(stranger.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            delete("/api/me/sessions/" + UUID.randomUUID())
                .header("Authorization", bearer(stranger.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            jsonPost("/api/me/sessions/logout-all", java.util.Map.of())
                .header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(stranger.accessToken())))
        .andExpect(status().isOk());
  }

  @Test
  void ownerCanListDevicesAndImmediatelyRevokeOneCopiedAccessToken() throws Exception {
    String email = UUID.randomUUID() + "@example.com";
    var first = register(email, "password123", "Session owner");
    var second = login(email, "password123");
    var listed =
        mockMvc
            .perform(get("/api/me/sessions").header("Authorization", bearer(second.accessToken())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn();
    var rows = objectMapper.readTree(listed.getResponse().getContentAsString());
    String other = null;
    int current = 0;
    for (var row : rows) {
      if (row.get("current").asBoolean()) current++;
      else other = row.get("id").asText();
    }
    org.junit.jupiter.api.Assertions.assertEquals(1, current);
    org.junit.jupiter.api.Assertions.assertNotNull(other);
    mockMvc
        .perform(
            delete("/api/me/sessions/" + other)
                .header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(first.accessToken())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(second.accessToken())))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/me/sessions").header("Authorization", bearer(second.accessToken())))
        .andExpect(jsonPath("$.length()").value(1));
  }
}
