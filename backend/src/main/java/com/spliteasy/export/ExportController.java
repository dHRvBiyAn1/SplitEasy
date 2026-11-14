package com.spliteasy.export;

import com.spliteasy.config.CurrentUserId;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ExportController {
  private final ExportService exportService;

  @GetMapping("/api/me/export")
  public void export(@CurrentUserId UUID userId, HttpServletResponse response) {
    // The export is always the caller's own, so there is no authorization failure to precede.
    response.setContentType("application/zip");
    response.setHeader(
        HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"spliteasy-export.zip\"");
    try {
      exportService.write(userId, response.getOutputStream());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
