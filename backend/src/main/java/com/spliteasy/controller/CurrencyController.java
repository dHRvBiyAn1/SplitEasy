package com.spliteasy.controller;

import com.spliteasy.dto.common.CurrencyInfo;
import com.spliteasy.entity.LedgerCurrency;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/currencies")
public class CurrencyController {
  @GetMapping
  public List<CurrencyInfo> catalogue() {
    return Arrays.stream(LedgerCurrency.values())
        .map(currency -> new CurrencyInfo(currency, currency.minorUnitExponent()))
        .toList();
  }
}
