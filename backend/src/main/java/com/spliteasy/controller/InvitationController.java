package com.spliteasy.controller;

import com.spliteasy.config.CurrentUserId;
import com.spliteasy.dto.invitation.InvitationDtos.*;
import com.spliteasy.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InvitationController {
  private final InvitationService invitations;
  private final AuthRateLimiter limits;

  @GetMapping("/groups/{groupId}/invitations")
  public List<Status> list(@CurrentUserId UUID actor, @PathVariable UUID groupId) {
    return invitations.list(actor, groupId);
  }

  @PostMapping("/groups/{groupId}/invitations/email")
  @ResponseStatus(HttpStatus.CREATED)
  public Status email(
      @CurrentUserId UUID actor,
      @PathVariable UUID groupId,
      @Valid @RequestBody EmailInvitation request,
      HttpServletRequest http) {
    limits.invitation(actor, groupId, http.getRemoteAddr());
    return invitations.email(actor, groupId, request);
  }

  @PostMapping("/groups/{groupId}/invitations/links")
  @ResponseStatus(HttpStatus.CREATED)
  public Link link(@CurrentUserId UUID actor, @PathVariable UUID groupId, HttpServletRequest http) {
    limits.invitation(actor, groupId, http.getRemoteAddr());
    return invitations.link(actor, groupId);
  }

  @DeleteMapping("/groups/{groupId}/invitations/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@CurrentUserId UUID actor, @PathVariable UUID groupId, @PathVariable UUID id) {
    invitations.revoke(actor, groupId, id);
  }

  @PostMapping("/invitations/preview")
  public Preview preview(
      @CurrentUserId UUID actor, @Valid @RequestBody Token request, HttpServletRequest http) {
    limits.invitationToken(actor, http.getRemoteAddr());
    return invitations.preview(actor, request);
  }

  @PostMapping("/invitations/accept")
  public Accepted accept(
      @CurrentUserId UUID actor, @Valid @RequestBody Token request, HttpServletRequest http) {
    limits.invitationToken(actor, http.getRemoteAddr());
    return invitations.accept(actor, request);
  }
}
