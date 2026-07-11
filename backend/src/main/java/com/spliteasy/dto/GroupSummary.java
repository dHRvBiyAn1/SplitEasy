package com.spliteasy.dto;

import com.spliteasy.entity.GroupType;
import java.util.UUID;

public record GroupSummary(UUID id, String name, GroupType type, long memberCount) {}
