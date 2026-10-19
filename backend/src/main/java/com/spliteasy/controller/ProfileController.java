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
