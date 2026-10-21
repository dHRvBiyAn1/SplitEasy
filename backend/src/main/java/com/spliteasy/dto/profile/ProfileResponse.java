package com.spliteasy.dto.profile;

import com.spliteasy.entity.User;
import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(
    UUID id,
    String email,
    boolean emailVerified,
    String displayName,
    String phone,
    String avatarColor,
    String preferredCurrency,
    Instant createdAt) {
  public static ProfileResponse from(User user) {
    return new ProfileResponse(
        user.getId(),
        user.getEmail(),
        user.isEmailVerified(),
        user.getDisplayName(),
        user.getPhone(),
        user.getAvatarColor(),
        user.getPreferredCurrency(),
        user.getCreatedAt());
  }
}
