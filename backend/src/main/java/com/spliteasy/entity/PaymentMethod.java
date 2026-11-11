package com.spliteasy.entity;

/**
 * How the people involved moved the money themselves. SplitEasy records a settlement; it never
 * transfers funds, so this is descriptive metadata, not an instruction to any payment rail.
 */
public enum PaymentMethod {
  CASH,
  BANK_TRANSFER,
  CARD,
  ONLINE,
  OTHER
}
