package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.profile.ProfileResponse;
import com.spliteasy.dto.profile.UpdateProfileRequest;
import com.spliteasy.service.ProfileService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class ProfileController {
  private final ProfileService profiles;
  private final com.spliteasy.service.AccountLifecycleService accounts;
  private final com.spliteasy.service.AuthRateLimiter rateLimiter;

  @PostMapping("/password")
  public org.springframework.http.ResponseEntity<Void> changePassword(
      @CurrentUserId UUID userId,
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.PasswordChange request,
      jakarta.servlet.http.HttpServletRequest http) {
    rateLimiter.sensitive(userId, http.getRemoteAddr());
    accounts.changePassword(userId, request);
    return org.springframework.http.ResponseEntity.noContent().build();
  }

  @PostMapping("/email-verification-requests")
  public org.springframework.http.ResponseEntity<Void> requestVerification(
      @CurrentUserId UUID userId, jakarta.servlet.http.HttpServletRequest http) {
    rateLimiter.sensitive(userId, http.getRemoteAddr());
    accounts.requestVerification(userId);
    return org.springframework.http.ResponseEntity.accepted().build();
  }

  @PostMapping("/email-change-requests")
  public org.springframework.http.ResponseEntity<Void> requestEmailChange(
      @CurrentUserId UUID userId,
      @Valid @RequestBody com.spliteasy.dto.auth.RecoveryRequests.EmailChange request,
      jakarta.servlet.http.HttpServletRequest http) {
    rateLimiter.sensitive(userId, http.getRemoteAddr());
    accounts.requestEmailChange(userId, request);
    return org.springframework.http.ResponseEntity.accepted().build();
  }

  @GetMapping
  public ProfileResponse get(@CurrentUserId UUID userId) {
    return profiles.get(userId);
  }

  @PatchMapping
  public ProfileResponse update(
      @CurrentUserId UUID userId, @Valid @RequestBody UpdateProfileRequest patch) {
    return profiles.update(userId, patch);
  }
}
