package com.spliteasy.dto.group;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Restores a recoverable group. The expected lifecycle version is mandatory, so a stale recovery
 * screen cannot resurrect a group whose state has moved on since it was listed.
 */
public record RestoreGroupRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
