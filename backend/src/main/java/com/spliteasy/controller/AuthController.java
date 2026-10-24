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

  private final com.spliteasy.service.AccountLifecycleService accounts;

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
    rateLimiter.register(http.getRemoteAddr());
    return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
  }

  @PostMapping("/login")
  public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    rateLimiter.login(request.email(), http.getRemoteAddr());
    return authService.login(request);
  }
}
