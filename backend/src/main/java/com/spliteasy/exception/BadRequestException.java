package com.spliteasy.exception;

/** Maps to HTTP 400 — a syntactically valid request that violates a domain rule. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
