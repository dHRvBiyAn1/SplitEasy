package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.AuthSessionService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/sessions")
@RequiredArgsConstructor
public class SessionController {
  private final AuthSessionService sessions;

  @GetMapping
  public List<com.spliteasy.dto.auth.DeviceSession> list(
      @CurrentUserId UUID userId, @AuthenticationPrincipal Jwt jwt) {
    return sessions.list(userId, UUID.fromString(jwt.getClaimAsString("sid")));
  }

  @PostMapping("/logout-all")
  public ResponseEntity<Void> logoutAll(@CurrentUserId UUID userId) {
    sessions.revokeAll(userId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> revoke(@CurrentUserId UUID userId, @PathVariable UUID id) {
    sessions.revoke(userId, id);
    return ResponseEntity.noContent().build();
  }
}
