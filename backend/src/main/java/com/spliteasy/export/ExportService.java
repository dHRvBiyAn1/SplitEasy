package com.spliteasy.export;

import com.spliteasy.dto.history.HistoryFilter;
import com.spliteasy.service.HistoryCsvExport;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * P07-A account export: profile, accessible groups and one H05 CSV per group and personal ledger,
 * streamed entry by entry from one consistent snapshot. Scopes come from the caller's own
 * memberships, so nothing outside them can appear.
 */
@Service
@RequiredArgsConstructor
public class ExportService {
  private final ExportQuery query;
  private final HistoryCsvExport csv;
  private final ObjectMapper json;

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public void write(UUID viewer, OutputStream out) {
    try {
      var zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
      entry(zip, "profile.json", w -> w.write(json.writeValueAsString(query.profile(viewer))));
      entry(zip, "groups.json", w -> w.write(json.writeValueAsString(query.groups(viewer))));
      var ledgers = query.personalLedgers(viewer);
      entry(zip, "personal-ledgers.json", w -> w.write(json.writeValueAsString(ledgers)));
      for (var g : query.groups(viewer))
        entry(zip, "groups/" + g.id() + ".csv", w -> csv.write(viewer, scope(g.id()), w));
      for (var l : ledgers)
        entry(zip, "personal/" + l.id() + ".csv", w -> csv.write(viewer, scope(l.id()), w));
      zip.finish();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static HistoryFilter scope(UUID groupId) {
    return new HistoryFilter(groupId, null, null, null, null, null);
  }

  /** Jackson would close the writer, and with it the archive, so JSON is written as a string. */
  private interface Body {
    void write(OutputStreamWriter writer) throws IOException;
  }

  private static void entry(ZipOutputStream zip, String name, Body body) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    // Flushed, not closed: closing the writer would close the whole archive.
    var writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
    body.write(writer);
    writer.flush();
    zip.closeEntry();
  }
}
