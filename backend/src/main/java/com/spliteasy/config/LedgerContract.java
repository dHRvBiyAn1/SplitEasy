package com.spliteasy.config;

import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.exception.ConflictException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Legacy clients understand USD cents only; non-USD writers must declare the minor-unit contract.
 */
public final class LedgerContract {
  public static final String HEADER = "X-Ledger-Version";
  public static final String UPGRADE =
      "Reload or upgrade the app to use this multi-currency ledger safely.";

  private LedgerContract() {}

  public static void requireCurrency(LedgerCurrency currency) {
    if (currency != LedgerCurrency.USD
        && RequestContextHolder.getRequestAttributes()
            instanceof ServletRequestAttributes attributes
        && !"2".equals(attributes.getRequest().getHeader(HEADER))) {
      throw new ConflictException(UPGRADE);
    }
  }
}
