package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.history.HistoryFilter;
import com.spliteasy.dto.history.HistoryPage;
import com.spliteasy.service.HistoryService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account-wide history, narrowed by optional group/friend scope. It lives under {@code /groups} so
 * clients already send the ledger headers there; the literal path wins over {@code /{groupId}}.
 */
@RestController
@RequestMapping("/api/groups/history")
@RequiredArgsConstructor
public class HistoryController {

  private final HistoryService historyService;

  @GetMapping
  public HistoryPage page(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) UUID groupId,
      @RequestParam(required = false) UUID friendId,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant updatedAfter,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant updatedBefore,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return historyService.page(
        userId,
        new HistoryFilter(groupId, friendId, from, to, updatedAfter, updatedBefore),
        cursor,
        limit);
  }

  @GetMapping("/export")
  public void export(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) UUID groupId,
      @RequestParam(required = false) UUID friendId,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant updatedAfter,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant updatedBefore,
      HttpServletResponse response) {
    // Headers are set only once authorization passed, so a 403/404 can still be a JSON error.
    historyService.export(
        userId,
        new HistoryFilter(groupId, friendId, from, to, updatedAfter, updatedBefore),
        () -> {
          response.setContentType("text/csv;charset=UTF-8");
          response.setHeader(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"history.csv\"");
          try {
            return response.getWriter();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }
}
