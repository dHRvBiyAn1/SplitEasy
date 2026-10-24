package com.spliteasy.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.spliteasy.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.jwt.secret=" + JwtSecurityIntegrationTest.SIGNING_KEY)
class JwtSecurityIntegrationTest extends AbstractIntegrationTest {

  // At least 64 bytes allow HS512 with the same key, isolating algorithm rejection from key
  // mismatch.
  static final String SIGNING_KEY =
      "jwt-security-test-only-key-0000000000000000000000000000000000000000";

  private String userId;
  private String email;

  @BeforeEach
  void registerUser() throws Exception {
    email = UUID.randomUUID() + "@example.com";
    userId = register(email, "password123", "JWT test").user().id().toString();
  }

  @Test
  void protectedEndpointAcceptsCorrectlySignedUnexpiredToken() throws Exception {
    mockMvc
        .perform(
            get("/api/groups")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            "spliteasy",
                            Instant.now().plusSeconds(300),
                            JWSAlgorithm.HS256,
                            SIGNING_KEY))))
        .andExpect(status().isOk());
  }

  @Test
  void protectedEndpointRejectsExpiredToken() throws Exception {
    // Well outside the resource server's default 60-second clock skew.
    mockMvc
        .perform(
            get("/api/groups")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            "spliteasy",
                            Instant.now().minusSeconds(300),
                            JWSAlgorithm.HS256,
                            SIGNING_KEY))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointRejectsWrongIssuer() throws Exception {
    mockMvc
        .perform(
            get("/api/groups")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            "another-issuer",
                            Instant.now().plusSeconds(300),
                            JWSAlgorithm.HS256,
                            SIGNING_KEY))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointRejectsWrongSignature() throws Exception {
    mockMvc
        .perform(
            get("/api/groups")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            "spliteasy",
                            Instant.now().plusSeconds(300),
                            JWSAlgorithm.HS256,
                            "different-test-signing-key-000000000000000000000000000000000000000"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedEndpointRejectsAnotherHmacAlgorithmWithCorrectKey() throws Exception {
    mockMvc
        .perform(
            get("/api/groups")
                .header(
                    "Authorization",
                    bearer(
                        token(
                            "spliteasy",
                            Instant.now().plusSeconds(300),
                            JWSAlgorithm.HS512,
                            SIGNING_KEY))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void credentialClaimsFailClosedAndLegacyTokensStopWorkingAtCredentialChange() throws Exception {
    String legacy = credentialToken(userId, null, false);
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(legacy)))
        .andExpect(status().isOk());
    for (Object invalid :
        new Object[] {
          -1, 1, "0", 0.5, true, java.util.List.of(0), java.util.Map.of("version", 0), null
        }) {
      mockMvc
          .perform(
              get("/api/me")
                  .header("Authorization", bearer(credentialToken(userId, invalid, true))))
          .andExpect(status().isUnauthorized());
    }
    for (String invalidSubject : new String[] {"not-a-uuid", UUID.randomUUID().toString()}) {
      mockMvc
          .perform(
              get("/api/me")
                  .header("Authorization", bearer(credentialToken(invalidSubject, 0, true))))
          .andExpect(status().isUnauthorized());
    }
    mockMvc
        .perform(
            jsonPost(
                    "/api/me/password",
                    java.util.Map.of(
                        "currentPassword", "password123", "password", "jwtChangedPassword"))
                .header("Authorization", bearer(legacy)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(legacy)))
        .andExpect(status().isUnauthorized());
    var fresh = login(email, "jwtChangedPassword");
    mockMvc
        .perform(get("/api/me").header("Authorization", bearer(fresh.accessToken())))
        .andExpect(status().isOk());
  }

  private String credentialToken(String subject, Object version, boolean includeVersion)
      throws Exception {
    var claims = new java.util.HashMap<String, Object>();
    claims.put("iss", "spliteasy");
    claims.put("sub", subject);
    claims.put("iat", Instant.now().minusSeconds(30).getEpochSecond());
    claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
    if (includeVersion) claims.put("credentialVersion", version);
    var jwt =
        new com.nimbusds.jose.JWSObject(
            new JWSHeader(JWSAlgorithm.HS256), new com.nimbusds.jose.Payload(json(claims)));
    jwt.sign(new MACSigner(SIGNING_KEY.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }

  private String token(String issuer, Instant expiresAt, JWSAlgorithm algorithm, String key)
      throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(userId)
            .issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(expiresAt))
            .build();
    var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
    jwt.sign(new MACSigner(key.getBytes(StandardCharsets.UTF_8)));
    return jwt.serialize();
  }
}
