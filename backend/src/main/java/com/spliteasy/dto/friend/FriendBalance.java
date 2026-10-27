package com.spliteasy.dto.friend;

import com.spliteasy.config.LedgerContract;
import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.ConflictException;

public record FriendBalance(LedgerCurrency currency, long netMinor) {
  public FriendBalance {
    LedgerContract.requireCurrency(currency);
    if (netMinor < -9_007_199_254_740_991L || netMinor > 9_007_199_254_740_991L)
      throw new ConflictException(
          "This total exceeds the supported exact integer range. Use a smaller ledger scope.");
  }
}
