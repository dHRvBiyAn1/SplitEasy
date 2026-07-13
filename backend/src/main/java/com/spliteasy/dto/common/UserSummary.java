package com.spliteasy.dto.common;

import com.spliteasy.entity.User;
import java.util.UUID;

public record UserSummary(UUID id, String email, String displayName) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
