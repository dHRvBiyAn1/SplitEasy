package com.spliteasy.entity;

/** How an expense's total is divided among its participants. */
public enum SplitType {
  /** Divided evenly; payer absorbs remainder cents. */
  EQUAL,
  /** Each participant owes a typed amount; the amounts must sum to the total. */
  UNEQUAL,
  /** Each participant owes a typed percentage (basis points summing to 10000). */
  PERCENTAGE,
  /** Positive integer units, prorated with the payer absorbing remainder cents. */
  WEIGHTS,
  /** Signed cents added to equal shares; adjustments sum to zero. */
  ADJUSTMENTS
}
