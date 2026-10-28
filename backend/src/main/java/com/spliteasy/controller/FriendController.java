package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.friend.FriendDetail;
import com.spliteasy.dto.friend.FriendSummary;
import com.spliteasy.dto.friend.SaveFriendRequest;
import com.spliteasy.service.FriendService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {
  private final FriendService friends;

  @GetMapping
  public List<FriendSummary> list(@CurrentUserId UUID owner) {
    return friends.list(owner);
  }

  @GetMapping("/{id}")
  public FriendDetail detail(@CurrentUserId UUID owner, @PathVariable UUID id) {
    return friends.detail(owner, id);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  public void remove(@CurrentUserId UUID owner, @PathVariable UUID id) {
    friends.remove(owner, id);
  }

  @PostMapping
  public FriendSummary save(
      @CurrentUserId UUID owner, @Valid @RequestBody SaveFriendRequest request) {
    return friends.save(owner, request);
  }
}
