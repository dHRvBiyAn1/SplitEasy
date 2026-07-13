package com.spliteasy.dto.auth;

import com.spliteasy.dto.common.UserSummary;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user) {

    public static AuthResponse bearer(String accessToken, long expiresIn, UserSummary user) {
        return new AuthResponse(accessToken, "Bearer", expiresIn, user);
    }
}
