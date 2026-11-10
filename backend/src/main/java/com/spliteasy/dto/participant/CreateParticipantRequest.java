package com.spliteasy.dto.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateParticipantRequest(
    @NotBlank @Size(max = 100) String displayName, UUID operationId) {}
