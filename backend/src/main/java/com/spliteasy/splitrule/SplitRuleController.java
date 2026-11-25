package com.spliteasy.splitrule;

import com.spliteasy.config.CurrentUserId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// The variable must stay named groupId so PersonalLedgerRouteGuard covers these routes.
@RestController
@RequestMapping("/api/groups/{groupId}/split-rules")
@RequiredArgsConstructor
public class SplitRuleController {
  private final SplitRuleService service;

  @GetMapping
  public List<SplitRuleResponse> list(@CurrentUserId UUID userId, @PathVariable UUID groupId) {
    return service.list(userId, groupId);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public SplitRuleResponse create(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @Valid @RequestBody SplitRuleRequest request) {
    return service.create(userId, groupId, request);
  }

  @PutMapping("/{ruleId}")
  public SplitRuleResponse update(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID ruleId,
      @Valid @RequestBody SplitRuleRequest request) {
    return service.update(userId, groupId, ruleId, request);
  }

  @DeleteMapping("/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @CurrentUserId UUID userId,
      @PathVariable UUID groupId,
      @PathVariable UUID ruleId,
      @RequestParam long expectedVersion) {
    service.delete(userId, groupId, ruleId, expectedVersion);
  }
}
