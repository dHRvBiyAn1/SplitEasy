package com.spliteasy.dto.group;

import com.spliteasy.entity.GroupType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param type null defaults to {@link GroupType#OTHER} (back-compat). */
public record CreateGroupRequest(
        @NotBlank @Size(max = 100) String name,
        GroupType type) {

    /** Back-compat convenience: name only, untyped. */
    public CreateGroupRequest(String name) {
        this(name, null);
    }
}
