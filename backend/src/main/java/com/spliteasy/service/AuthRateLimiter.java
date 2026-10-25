package com.spliteasy.service;

import com.spliteasy.exception.TooManyRequestsException;
import com.spliteasy.util.Emails;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Atomic fixed-window attempt budgets, before credential lookup or BCrypt. */
@Component
public class AuthRateLimiter {
  private static final int MAX_BUCKETS = 10_000;
  private static final long LOGIN_WINDOW = Duration.ofMinutes(5).toNanos();
  private static final long REGISTER_WINDOW = Duration.ofHours(1).toNanos();
  private final Map<Key, Window> windows = new HashMap<>();
  private final LongSupplier nanoTime;
  private final int registrationLimit;

  @Autowired
  public AuthRateLimiter(@Value("${app.auth.registration-limit:20}") int registrationLimit) {
    this(System::nanoTime, registrationLimit);
  }

  AuthRateLimiter(LongSupplier nanoTime) {
    this(nanoTime, 20);
  }

  private AuthRateLimiter(LongSupplier nanoTime, int registrationLimit) {
    if (registrationLimit < 1)
      throw new IllegalArgumentException("Registration limit must be positive");
    this.nanoTime = nanoTime;
    this.registrationLimit = registrationLimit;
  }

  public void login(String email, String ip) {
    take(
        LOGIN_WINDOW,
        new Budget(new Key("login", ip, ""), 30),
        new Budget(new Key("login", ip, Emails.normalize(email)), 5));
  }

  public void recovery(String email, String ip) {
    take(
        LOGIN_WINDOW,
        new Budget(new Key("recovery", ip, ""), 20),
        new Budget(new Key("recovery", "", Emails.normalize(email)), 5));
  }

  public void confirmation(String ip) {
    take(LOGIN_WINDOW, new Budget(new Key("confirmation", ip, ""), 30));
  }

  public void sensitive(java.util.UUID userId, String ip) {
    take(
        LOGIN_WINDOW,
        new Budget(new Key("sensitive", ip, ""), 20),
        new Budget(new Key("sensitive", "", userId.toString()), 5));
  }

  public void refresh(String ip) {
    take(LOGIN_WINDOW, new Budget(new Key("refresh", ip, ""), 120));
  }

  public void register(String ip) {
    take(REGISTER_WINDOW, new Budget(new Key("register", ip, ""), registrationLimit));
  }

  // shortcut: process-local budgets reset on restart; share state before running multiple
  // instances.
  private synchronized void take(long duration, Budget... budgets) {
    long now = nanoTime.getAsLong();
    long retry = 0;
    int missing = 0;
    for (Budget budget : budgets) {
      Window window = windows.get(budget.key());
      if (window == null) missing++;
      else if (window.expiresAt() - now > 0 && window.count() >= budget.limit()) {
        retry = Math.max(retry, window.expiresAt() - now);
      }
    }
    if (retry > 0) throw limited(retry);
    if (windows.size() + missing > MAX_BUCKETS) {
      windows.values().removeIf(window -> window.expiresAt() - now <= 0);
      missing =
          (int) java.util.Arrays.stream(budgets).filter(b -> !windows.containsKey(b.key())).count();
      if (windows.size() + missing > MAX_BUCKETS) {
        long earliest =
            windows.values().stream().mapToLong(w -> w.expiresAt() - now).min().orElse(duration);
        throw limited(earliest);
      }
    }
    for (Budget budget : budgets) {
      Window old = windows.get(budget.key());
      windows.put(
          budget.key(),
          old == null || old.expiresAt() - now <= 0
              ? new Window(1, now + duration)
              : new Window(old.count() + 1, old.expiresAt()));
    }
  }

  private TooManyRequestsException limited(long remainingNanos) {
    return new TooManyRequestsException(
        Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L));
  }

  private record Key(String action, String ip, String account) {}

  private record Budget(Key key, int limit) {}

  private record Window(int count, long expiresAt) {}
}
