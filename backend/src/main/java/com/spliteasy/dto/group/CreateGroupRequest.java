package com.spliteasy.dto.group;

import com.spliteasy.entity.GroupType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param type null defaults to {@link GroupType#OTHER} (back-compat).
 */
public record CreateGroupRequest(
    @NotBlank @Size(max = 100) String name, GroupType type, java.util.UUID operationId) {

  public CreateGroupRequest(String name, GroupType type) {
    this(name, type, null);
  }

  /** Back-compat convenience: name only, untyped. */
  public CreateGroupRequest(String name) {
    this(name, null, null);
  }
}
