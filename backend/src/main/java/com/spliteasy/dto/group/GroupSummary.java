package com.spliteasy.dto.group;

import com.spliteasy.entity.GroupType;
import java.util.UUID;

/**
 * @param archivedAt null while the group is open; set once it is read-only (migration V19)
 * @param lifecycleVersion restore precondition carried by the creator's recovery list
 */
public record GroupSummary(
    UUID id,
    String name,
    GroupType type,
    long memberCount,
    boolean simplifyDebts,
    java.time.Instant archivedAt,
    long lifecycleVersion) {

  /** Existing callers that predate the lifecycle state: an open group. */
  public GroupSummary(
      UUID id, String name, GroupType type, long memberCount, boolean simplifyDebts) {
    this(id, name, type, memberCount, simplifyDebts, null, 0L);
  }
}
