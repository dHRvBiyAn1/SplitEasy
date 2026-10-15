package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Component;

/** Each participant owes a typed amount (cents); the amounts must sum to the total. */
@Component
public class UnequalSplitStrategy implements SplitStrategy {

  @Override
  public SplitType type() {
    return SplitType.UNEQUAL;
  }

  @Override
  public List<Share> split(SplitContext ctx) {
    List<SplitInput> splits = ctx.splits();
    for (SplitInput split : splits) {
      if (split.value() < 0 || split.value() > ctx.totalCents()) {
        throw new BadRequestException(
            "Each exact share must be between 0 and the expense total (%dc)"
                .formatted(ctx.totalCents()));
      }
    }
    BigInteger sum =
        splits.stream()
            .map(s -> BigInteger.valueOf(s.value()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    if (!sum.equals(BigInteger.valueOf(ctx.totalCents()))) {
      throw new BadRequestException(
          "Entered amounts (%sc) must add up to the expense total (%dc)"
              .formatted(sum, ctx.totalCents()));
    }
    return splits.stream().map(s -> new Share(s.userId(), s.value())).toList();
  }
}
