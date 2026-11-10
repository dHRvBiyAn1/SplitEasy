package com.spliteasy.dto.dashboard;

import com.spliteasy.entity.GroupType;
import java.util.UUID;

/**
 * One group card on the dashboard, from the requesting user's perspective. {@code youAreOwedCents -
 * youOweCents == netCents}.
 */
public record DashboardGroup(
    UUID id,
    String name,
    GroupType type,
    long memberCount,
    Long totalSpentCents,
    Long youAreOwedCents,
    Long youOweCents,
    Long netCents,
    /** Group setting: settle-up uses suggested netted transfers for this group. */
    boolean simplifyDebts,
    java.util.List<GroupCurrencyTotals> totals) {
  @com.fasterxml.jackson.annotation.JsonProperty("totalSpentMinor")
  public Long totalSpentMinor() {
    return totalSpentCents;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("youAreOwedMinor")
  public Long youAreOwedMinor() {
    return youAreOwedCents;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("youOweMinor")
  public Long youOweMinor() {
    return youOweCents;
  }

  @com.fasterxml.jackson.annotation.JsonProperty("netMinor")
  public Long netMinor() {
    return netCents;
  }
}
