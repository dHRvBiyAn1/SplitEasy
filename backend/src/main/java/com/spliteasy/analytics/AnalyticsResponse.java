package com.spliteasy.analytics;

import com.spliteasy.entity.LedgerCurrency;
import java.util.List;

/** Per-currency spending rows; currencies are never summed together. */
public record AnalyticsResponse(List<Row> rows) {
  /** Refunds are already subtracted; paid comes from contributions. */
  public record Row(
      LedgerCurrency currency,
      String key,
      String label,
      long groupTotalMinor,
      long myShareMinor,
      long myPaidMinor) {}
}
