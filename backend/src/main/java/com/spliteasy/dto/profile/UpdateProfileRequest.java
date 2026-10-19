package com.spliteasy.dto.profile;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Null/omitted fields are unchanged; an empty phone clears unverified contact information. */
public record UpdateProfileRequest(
    @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String displayName,
    @Size(max = 50) String phone,
    @Pattern(regexp = "#12A57E|#D96D3F|#3F7BD9|#C25B84|#D9A03F") String avatarColor,
    @Pattern(regexp = "USD|EUR|GBP|INR") String preferredCurrency) {}
