package com.spliteasy.dto.auth;

import com.spliteasy.dto.common.UserSummary;

public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    UserSummary user,
    java.util.UUID sessionId) {

  public static AuthResponse bearer(
      String accessToken, long expiresIn, UserSummary user, java.util.UUID sessionId) {
    return new AuthResponse(accessToken, "Bearer", expiresIn, user, sessionId);
  }
}
