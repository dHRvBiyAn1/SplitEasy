package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.dto.participant.CreateParticipantRequest;
import com.spliteasy.service.GroupParticipantService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/groups/{groupId}/participants")
@RequiredArgsConstructor
public class GroupParticipantController {
  private final GroupParticipantService participants;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ParticipantSummary create(
      @CurrentUserId UUID actor,
      @PathVariable UUID groupId,
      @Valid @RequestBody CreateParticipantRequest request) {
    return participants.create(actor, groupId, request);
  }

  @DeleteMapping("/{participantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @CurrentUserId UUID actor, @PathVariable UUID groupId, @PathVariable UUID participantId) {
    participants.remove(actor, groupId, participantId);
  }

  @PostMapping("/{participantId}/reactivate")
  public ParticipantSummary reactivate(
      @CurrentUserId UUID actor, @PathVariable UUID groupId, @PathVariable UUID participantId) {
    return participants.reactivate(actor, groupId, participantId);
  }
}
