package com.spliteasy.repository;

import java.util.UUID;

/** Grouped cents from one financial participant to another. */
public interface PairAmount {
  UUID getFromId();

  UUID getToId();

  long getTotalCents();
}
