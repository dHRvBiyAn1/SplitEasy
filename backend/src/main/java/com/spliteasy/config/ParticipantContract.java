package com.spliteasy.config;

import com.spliteasy.exception.ConflictException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;

/** Wire capability, never an access permission. */
public final class ParticipantContract {
  public static final String HEADER = "X-Participant-Version";
  public static final String UPGRADE =
      "Reload or upgrade the app to use financial participants safely.";

  private ParticipantContract() {}

  public static boolean enabled() {
    return !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)
        || "1".equals(a.getRequest().getHeader(HEADER));
  }

  public static void requireEnabled() {
    if (!enabled()) throw new ConflictException(UPGRADE);
  }

  public static void requireReceipt(JsonNode value) {
    if (enabled()) return;
    if (value.isObject()
        && value.has("id")
        && value.has("displayName")
        && value.has("email")
        && (value.path("email").isNull()
            || (value.hasNonNull("canonicalParticipantId")
                && !value.path("id").equals(value.path("canonicalParticipantId")))))
      throw new ConflictException(UPGRADE);
    if (value.isObject() || value.isArray()) for (var child : value) requireReceipt(child);
  }
}
