package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.profile.CloseAccountRequest;
import com.spliteasy.service.AccountClosureService;
import com.spliteasy.service.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me/closure")
@RequiredArgsConstructor
public class AccountClosureController {
  private final AccountClosureService closure;
  private final AuthRateLimiter rateLimiter;

  @PostMapping
  public ResponseEntity<Void> close(
      @CurrentUserId UUID userId,
      @Valid @RequestBody CloseAccountRequest request,
      HttpServletRequest http) {
    rateLimiter.sensitive(userId, http.getRemoteAddr());
    closure.close(userId, request.currentPassword());
    return ResponseEntity.noContent().build();
  }
}
