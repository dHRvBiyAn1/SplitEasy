package com.spliteasy.exception;

public class TooManyRequestsException extends RuntimeException {
  private final long retryAfterSeconds;

  public TooManyRequestsException(long retryAfterSeconds) {
    super("Too many authentication attempts. Please try again later.");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
