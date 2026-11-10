package com.spliteasy.controller;

import com.spliteasy.dto.auth.AuthResponse;
import com.spliteasy.dto.auth.LoginRequest;
import com.spliteasy.dto.auth.RegisterRequest;
import com.spliteasy.service.AuthRateLimiter;
import com.spliteasy.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final AuthRateLimiter rateLimiter;
  private final org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource;

  private final com.spliteasy.service.AccountLifecycleService accounts;

  private final com.spliteasy.service.AuthSessionService sessions;

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @com.spliteasy.config.CurrentUserId java.util.UUID userId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          org.springframework.security.oauth2.jwt.Jwt jwt) {
    sessions.revoke(userId, java.util.UUID.fromString(jwt.getClaimAsString("sid")));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password-reset-requests")
  public ResponseEntity<Void> requestReset(
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.EmailRequest request,
      HttpServletRequest http) {
    rateLimiter.recovery(request.email(), http.getRemoteAddr());
    try {
      accounts.requestReset(request.email());
    } catch (com.spliteasy.exception.MailDeliveryFailedException ex) {
      // Generic acceptance preserves account privacy even when an actual send fails.
    }
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/password-resets")
  public ResponseEntity<Void> resetPassword(
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.PasswordReset request,
      HttpServletRequest http) {
    rateLimiter.confirmation(http.getRemoteAddr());
    accounts.resetPassword(request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/email-verifications")
  public ResponseEntity<Void> verifyEmail(
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.TokenRequest request,
      HttpServletRequest http) {
    rateLimiter.confirmation(http.getRemoteAddr());
    accounts.verifyEmail(request.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/email-change-confirmations")
  public ResponseEntity<Void> confirmEmailChange(
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.TokenRequest request,
      HttpServletRequest http) {
    rateLimiter.confirmation(http.getRemoteAddr());
    accounts.confirmEmailChange(request.token());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    requireBrowserOrigin(http, false);
    rateLimiter.register(http.getRemoteAddr());
    return issued(authService.register(request, http.getHeader("User-Agent")), HttpStatus.CREATED);
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    requireBrowserOrigin(http, false);
    rateLimiter.login(request.email(), http.getRemoteAddr());
    return issued(authService.login(request, http.getHeader("User-Agent")), HttpStatus.OK);
  }

  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refresh(
      @org.springframework.web.bind.annotation.CookieValue(
              name = "__Host-spliteasy-refresh",
              required = false)
          String token,
      HttpServletRequest http) {
    requireBrowserOrigin(http, true);
    rateLimiter.refresh(http.getRemoteAddr());
    var result = sessions.refresh(token);
    return result == null
        ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        : issued(result, HttpStatus.OK);
  }

  private void requireBrowserOrigin(HttpServletRequest http, boolean refresh) {
    String origin = http.getHeader("Origin");
    var config = corsConfigurationSource.getCorsConfiguration(http);
    if ((refresh && (origin == null || !"1".equals(http.getHeader("X-SplitEasy-Session"))))
        || (origin != null && (config == null || config.checkOrigin(origin) == null))) {
      throw new com.spliteasy.exception.ForbiddenException(
          "Browser session request is not allowed");
    }
  }

  private ResponseEntity<AuthResponse> issued(
      com.spliteasy.service.AuthSessionService.IssuedSession result, HttpStatus status) {
    var cookie =
        org.springframework.http.ResponseCookie.from(
                "__Host-spliteasy-refresh", result.refreshCredential())
            .httpOnly(true)
            .secure(true)
            .path("/")
            .sameSite("Strict")
            .maxAge(result.cookieSeconds())
            .build();
    return ResponseEntity.status(status)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .header("Set-Cookie", cookie.toString())
        .body(result.response());
  }
}
