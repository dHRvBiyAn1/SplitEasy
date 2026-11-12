package com.spliteasy.reminder;

import com.spliteasy.config.CurrentUserId;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups/{groupId}/reminders")
@RequiredArgsConstructor
public class ReminderController {
  private final ReminderService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ReminderResponse remind(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @Valid @RequestBody ReminderRequest request) {
    return service.remind(userId, groupId, request);
  }
}
