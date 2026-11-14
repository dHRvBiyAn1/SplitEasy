package com.spliteasy.dto.profile;

import jakarta.validation.constraints.NotBlank;

public record CloseAccountRequest(@NotBlank String currentPassword) {}
