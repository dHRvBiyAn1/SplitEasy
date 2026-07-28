package com.spliteasy.dto.group;

import com.spliteasy.entity.GroupType;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a group's settings — every field is optional and a null one is left
 * untouched, so the settings page can PATCH just the field it changed (rename, group type,
 * or the simplify-debts preference).
 */
public record UpdateGroupRequest(
        @Size(min = 1, max = 100, message = "must be between 1 and 100 characters") String name,
        GroupType type,
        Boolean simplifyDebts) {}
