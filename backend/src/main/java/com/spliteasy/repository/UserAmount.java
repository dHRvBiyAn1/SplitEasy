package com.spliteasy.repository;

import java.util.UUID;

/** Aggregate projection: a per-user summed amount in integer cents. */
public interface UserAmount {
    UUID getUserId();

    long getTotalCents();
}
