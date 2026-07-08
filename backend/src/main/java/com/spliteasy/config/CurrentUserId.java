package com.spliteasy.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a controller {@code UUID} parameter to the authenticated caller's id
 * (the JWT {@code sub} claim). One source of truth for extracting the user id,
 * replacing the repeated {@code UUID.fromString(jwt.getSubject())}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {}
