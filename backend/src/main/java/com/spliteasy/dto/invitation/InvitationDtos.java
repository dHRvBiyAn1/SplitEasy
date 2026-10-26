package com.spliteasy.dto.invitation;

import com.spliteasy.dto.common.ParticipantSummary;
import com.spliteasy.entity.LedgerCurrency;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class InvitationDtos {
  private InvitationDtos() {}

  public record EmailInvitation(
      @NotBlank @Email @Size(max = 254) String email,
      UUID participantId,
      UUID operationId,
      Boolean resend) {}

  public record Token(@NotBlank @Size(max = 128) String token, Boolean confirmOwnership) {}

  public record Status(
      UUID id,
      String kind,
      UUID participantId,
      String participantName,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant acceptedAt) {}

  public record Link(Status invitation, String url) {}

  public record ClaimBalance(
      LedgerCurrency currency,
      long existingNetMinor,
      long participantNetMinor,
      long resultingNetMinor) {}

  public record Preview(
      UUID invitationId,
      String kind,
      UUID groupId,
      String groupName,
      Instant expiresAt,
      ParticipantSummary participant,
      boolean requiresOwnershipConfirmation,
      List<ClaimBalance> balances) {}

  public record Accepted(UUID groupId, UUID participantId, UUID accountId, boolean claimed) {}
}
