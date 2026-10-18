package com.spliteasy.service;

import static org.junit.jupiter.api.Assertions.*;

import com.spliteasy.exception.TooManyRequestsException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {
  private final AtomicLong nanos = new AtomicLong();
  private final AuthRateLimiter limiter = new AuthRateLimiter(nanos::get);

  @Test
  void accountIsNormalizedAndBlockedAttemptsDoNotExtendWindow() {
    for (int i = 0; i < 5; i++) limiter.login(" Alice@example.com ", "198.51.100.1");
    nanos.addAndGet(Duration.ofSeconds(1).toNanos());
    assertEquals(
        299,
        assertThrows(
                TooManyRequestsException.class,
                () -> limiter.login("alice@example.com", "198.51.100.1"))
            .retryAfterSeconds());
    nanos.addAndGet(Duration.ofSeconds(299).toNanos());
    assertDoesNotThrow(() -> limiter.login("alice@example.com", "198.51.100.1"));
  }

  @Test
  void ipLimitCannotBeBypassedByRotatingAccounts() {
    for (int i = 0; i < 30; i++) limiter.login("user" + i + "@example.com", "198.51.100.1");
    assertEquals(
        300,
        assertThrows(
                TooManyRequestsException.class,
                () -> limiter.login("another@example.com", "198.51.100.1"))
            .retryAfterSeconds());
    assertDoesNotThrow(() -> limiter.login("another@example.com", "198.51.100.2"));
  }

  @Test
  void accountsIpsAndEndpointsAreIndependent() {
    for (int i = 0; i < 5; i++) limiter.login("alice@example.com", "198.51.100.1");
    assertDoesNotThrow(() -> limiter.login("bob@example.com", "198.51.100.1"));
    assertDoesNotThrow(() -> limiter.login("alice@example.com", "198.51.100.2"));
    assertDoesNotThrow(() -> limiter.register("198.51.100.1"));
  }

  @Test
  void registrationHasAHourWindowAndCeilingRoundedRetry() {
    for (int i = 0; i < 20; i++) limiter.register("198.51.100.1");
    nanos.addAndGet(1);
    assertEquals(
        3600,
        assertThrows(TooManyRequestsException.class, () -> limiter.register("198.51.100.1"))
            .retryAfterSeconds());
    nanos.set(Duration.ofHours(1).toNanos());
    assertDoesNotThrow(() -> limiter.register("198.51.100.1"));
  }

  @Test
  void concurrentRequestsCannotExceedTheAccountLimit() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          executor.invokeAll(
              java.util.stream.IntStream.range(0, 100)
                  .<java.util.concurrent.Callable<Boolean>>mapToObj(
                      i ->
                          () -> {
                            try {
                              limiter.login("alice@example.com", "198.51.100.1");
                              return true;
                            } catch (TooManyRequestsException ex) {
                              return false;
                            }
                          })
                  .toList());
      long accepted = 0;
      for (var result : results) if (result.get()) accepted++;
      assertEquals(5, accepted);
    }
  }

  @Test
  void capacityFailsClosedWithoutEvictingActiveLimitsAndRecoversAfterExpiry() {
    for (int i = 0; i < 10_000; i++) limiter.register("client-" + i);
    assertEquals(
        3600,
        assertThrows(TooManyRequestsException.class, () -> limiter.register("new-client"))
            .retryAfterSeconds());
    for (int i = 1; i < 20; i++) limiter.register("client-0");
    assertThrows(TooManyRequestsException.class, () -> limiter.register("client-0"));
    nanos.set(Duration.ofHours(1).toNanos());
    assertDoesNotThrow(() -> limiter.register("new-client"));
  }
}
