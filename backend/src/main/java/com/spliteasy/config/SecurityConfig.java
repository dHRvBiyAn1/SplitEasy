package com.spliteasy.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  private final SecretKey jwtKey;
  private final String issuer;

  public SecurityConfig(
      @Value("${app.jwt.secret}") String secret, @Value("${app.jwt.issuer}") String issuer) {
    byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    if (keyBytes.length < 32) {
      throw new IllegalStateException(
          "app.jwt.secret must be at least 32 bytes (256 bits) for HS256; got " + keyBytes.length);
    }
    this.jwtKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    this.issuer = issuer;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> {})
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/api/auth/logout")
                    .authenticated()
                    .requestMatchers("/api/auth/**", "/api/health")
                    .permitAll()
                    // Internal container probe; Caddy blocks all /actuator routes.
                    .requestMatchers(HttpMethod.GET, "/actuator/health/readiness")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public JwtEncoder jwtEncoder() {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
  }

  @Bean
  public JwtDecoder jwtDecoder(
      com.spliteasy.repository.UserRepository users,
      com.spliteasy.service.AuthSessionService sessions) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
    org.springframework.security.oauth2.core.OAuth2TokenValidator<
            org.springframework.security.oauth2.jwt.Jwt>
        credentials =
            jwt -> {
              var failure =
                  org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                      new org.springframework.security.oauth2.core.OAuth2Error(
                          "invalid_token", "Access token is no longer valid", null));
              try {
                Object raw = jwt.getClaims().get("credentialVersion");
                long version = 0;
                if (jwt.getClaims().containsKey("credentialVersion")) {
                  if (!(raw instanceof Integer || raw instanceof Long)) return failure;
                  version = ((Number) raw).longValue();
                  if (version < 0) return failure;
                }
                if (!sessions.active(
                    java.util.UUID.fromString(jwt.getSubject()),
                    java.util.UUID.fromString(jwt.getClaimAsString("sid")))) return failure;
                Long current =
                    users
                        .findCredentialVersion(java.util.UUID.fromString(jwt.getSubject()))
                        .orElse(null);
                return current != null && current.longValue() == version
                    ? org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success()
                    : failure;
              } catch (RuntimeException ex) {
                return failure;
              }
            };
    decoder.setJwtValidator(
        new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), credentials));
    return decoder;
  }
}
