package com.spliteasy.service;

import com.spliteasy.exception.BadRequestException;
import com.spliteasy.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ExpenseVersionPolicy {
  private final Clock clock;
  private final Instant requiredFrom;

  public ExpenseVersionPolicy(
      @Qualifier("financialWriteClock") Clock clock,
      @Value("${app.expenses.version-required-from:2026-11-19T00:00:00Z}") String requiredFrom) {
    this.clock = clock;
    this.requiredFrom = Instant.parse(requiredFrom);
  }

  @Bean("financialWriteClock")
  static Clock financialWriteClock() {
    return Clock.systemUTC();
  }

  public void requireExpected(Long expected, long actual) {
    if (expected == null) {
      if (!clock.instant().isBefore(requiredFrom)) {
        throw new BadRequestException(
            "expectedVersion is required. Reload or upgrade your app before editing or deleting an expense.");
      }
      return;
    }
    if (expected < 0) throw new BadRequestException("expectedVersion must be nonnegative");
    if (expected != actual) {
      throw new ConflictException(
          "This expense changed. Reload and review the latest changes before saving or deleting again.");
    }
  }
}
