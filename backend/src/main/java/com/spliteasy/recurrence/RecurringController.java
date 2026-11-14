package com.spliteasy.recurrence;

import com.spliteasy.config.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups/{groupId}/recurring")
@RequiredArgsConstructor
public class RecurringController {
  private final RecurringService service;

  @GetMapping
  public List<ScheduleResponse> list(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return service.list(userId, groupId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ScheduleResponse create(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @Valid @RequestBody ScheduleRequest request) {
    return service.create(userId, groupId, request);
  }

  @GetMapping("/{id}")
  public ScheduleResponse get(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID id) {
    return service.get(userId, groupId, id);
  }

  @PutMapping("/{id}")
  public ScheduleResponse update(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID id,
      @Valid @RequestBody ScheduleRequest request) {
    return service.update(userId, groupId, id, request);
  }

  @PostMapping("/{id}/cancel")
  public ScheduleResponse cancel(
      @CurrentUserId UUID userId, @PathVariable UUID groupId, @PathVariable UUID id) {
    return service.cancel(userId, groupId, id);
  }
}
