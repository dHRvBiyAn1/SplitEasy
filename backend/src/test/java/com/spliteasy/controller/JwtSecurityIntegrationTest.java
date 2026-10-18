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

  // At least 64 bytes allow HS512 with the same key, isolating algorithm rejection from key mismatch.
  static final String SIGNING_KEY =
      "jwt-security-test-only-key-0000000000000000000000000000000000000000";

  private String userId;

  @BeforeEach
  void registerUser() throws Exception {
    userId =
        register(UUID.randomUUID() + "@example.com", "password123", "JWT test")
            .user()
            .id()
            .toString();
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
