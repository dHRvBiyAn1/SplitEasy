package com.spliteasy.service;

import com.spliteasy.dto.profile.ProfileResponse;
import com.spliteasy.dto.profile.UpdateProfileRequest;
import com.spliteasy.entity.User;
import com.spliteasy.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileService {
  private final UserRepository users;

  @Transactional(readOnly = true)
  public ProfileResponse get(UUID userId) {
    return ProfileResponse.from(requireUser(userId));
  }

  @Transactional
  public ProfileResponse update(UUID userId, UpdateProfileRequest patch) {
    User user = requireUser(userId);
    if (patch.displayName() != null) user.setDisplayName(patch.displayName().trim());
    if (patch.phone() != null) user.setPhone(patch.phone().trim());
    if (patch.avatarColor() != null) user.setAvatarColor(patch.avatarColor());
    if (patch.preferredCurrency() != null) user.setPreferredCurrency(patch.preferredCurrency());
    return ProfileResponse.from(user);
  }

  private User requireUser(UUID userId) {
    return users
        .findById(userId)
        .orElseThrow(() -> new BadCredentialsException("Account unavailable"));
  }
}
