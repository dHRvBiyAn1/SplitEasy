package com.spliteasy.entity;

/** Deliberately finite supported catalogue; stored amounts are integer minor units. */
public enum LedgerCurrency {
  USD(2),
  EUR(2),
  GBP(2),
  INR(2),
  JPY(0),
  KWD(3);
  private final int minorUnitExponent;

  LedgerCurrency(int exponent) {
    this.minorUnitExponent = exponent;
  }

  public int minorUnitExponent() {
    return minorUnitExponent;
  }
}
