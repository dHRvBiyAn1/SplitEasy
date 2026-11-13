package com.spliteasy.service;

import com.spliteasy.dto.history.HistoryFilter;
import com.spliteasy.repository.HistoryQuery;
import java.io.Writer;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Public door to H05's package-private CSV writer for other packages (account export). It does no
 * authorization: the caller must only pass scopes it has already authorized.
 */
@Component
@RequiredArgsConstructor
public class HistoryCsvExport {
  private final HistoryQuery query;

  /** Header plus every matching row, streamed; the caller owns the writer and the transaction. */
  public void write(UUID viewer, HistoryFilter filter, Writer out) {
    var csv = new HistoryCsvWriter(out);
    csv.header();
    query.export(viewer, filter, csv::row);
  }
}
