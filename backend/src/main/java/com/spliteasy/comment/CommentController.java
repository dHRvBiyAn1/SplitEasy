package com.spliteasy.comment;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.service.PersonalLedgerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Group routes bind {@code groupId} (the personal-ledger guard needs it); friend routes mirror
 * them.
 */
@RestController
@RequiredArgsConstructor
public class CommentController {
  private final CommentService service;
  private final PersonalLedgerService personal;

  private static final String GROUP = "/api/groups/{groupId}/expenses/{expenseId}/comments";
  private static final String FRIEND = "/api/friends/{friendId}/expenses/{expenseId}/comments";

  @GetMapping(GROUP)
  public List<CommentResponse> list(
      @CurrentUserId UUID user, @PathVariable UUID groupId, @PathVariable UUID expenseId) {
    return service.list(user, groupId, expenseId);
  }

  @PostMapping(GROUP)
  @ResponseStatus(HttpStatus.CREATED)
  public CommentResponse create(
      @CurrentUserId UUID user,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody CommentRequest request) {
    return service.create(user, groupId, expenseId, request);
  }

  @DeleteMapping(GROUP + "/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @CurrentUserId UUID user,
      @PathVariable UUID groupId,
      @PathVariable UUID expenseId,
      @PathVariable UUID commentId) {
    service.delete(user, groupId, expenseId, commentId);
  }

  @GetMapping(FRIEND)
  public List<CommentResponse> listPersonal(
      @CurrentUserId UUID user, @PathVariable UUID friendId, @PathVariable UUID expenseId) {
    return service.list(user, ledger(user, friendId, expenseId), expenseId);
  }

  @PostMapping(FRIEND)
  @ResponseStatus(HttpStatus.CREATED)
  public CommentResponse createPersonal(
      @CurrentUserId UUID user,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @Valid @RequestBody CommentRequest request) {
    return service.create(user, ledger(user, friendId, expenseId), expenseId, request);
  }

  @DeleteMapping(FRIEND + "/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePersonal(
      @CurrentUserId UUID user,
      @PathVariable UUID friendId,
      @PathVariable UUID expenseId,
      @PathVariable UUID commentId) {
    service.delete(user, ledger(user, friendId, expenseId), expenseId, commentId);
  }

  /** Authorizes through the personal-ledger service and returns the hidden ledger's group id. */
  private UUID ledger(UUID user, UUID friendId, UUID expenseId) {
    return personal.get(user, friendId, expenseId).groupId();
  }
}
