package com.spliteasy.service.split;

import java.util.UUID;

/** One participant's computed share of an expense, in integer cents. */
public record Share(UUID userId, long shareCents) {}
