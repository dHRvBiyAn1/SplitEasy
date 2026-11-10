package com.spliteasy.dto.friend;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SaveFriendRequest(@NotNull UUID groupId, @NotNull UUID participantId) {}
