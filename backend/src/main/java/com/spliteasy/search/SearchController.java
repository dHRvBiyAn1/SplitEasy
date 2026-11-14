package com.spliteasy.search;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.history.HistoryPage;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {
  private final SearchService searchService;

  @GetMapping
  public HistoryPage search(
      @CurrentUserId UUID userId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) UUID groupId,
      @RequestParam(required = false) UUID friendId,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) Long minAmount,
      @RequestParam(required = false) Long maxAmount,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return searchService.search(
        userId,
        new SearchService.Request(
            q, groupId, friendId, from, to, currency, minAmount, maxAmount, cursor, limit));
  }
}
