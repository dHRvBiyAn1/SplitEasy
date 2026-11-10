package com.spliteasy.dto.auth;

import jakarta.validation.constraints.*;

public final class RecoveryRequests {
  private RecoveryRequests() {}

  public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    public EmailRequest {
      if (email != null) email = com.spliteasy.util.Emails.normalize(email);
    }
  }

  public record PasswordChange(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Size(min = 8, max = 72) String password) {}

  public record EmailChange(
      @NotBlank @Size(max = 72) String currentPassword,
      @NotBlank @Email @Size(max = 254) String email) {
    public EmailChange {
      if (email != null) email = com.spliteasy.util.Emails.normalize(email);
    }
  }

  public record TokenRequest(@NotBlank @Size(max = 100) String token) {}

  public record PasswordReset(
      @NotBlank @Size(max = 100) String token,
      @NotBlank @Size(min = 8, max = 72) String password) {}
}
