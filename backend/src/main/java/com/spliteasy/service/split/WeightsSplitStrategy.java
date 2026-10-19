package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Positive integer units; floor each allocation and give the payer the remainder. */
@Component
public class WeightsSplitStrategy implements SplitStrategy {
  @Override
  public SplitType type() {
    return SplitType.WEIGHTS;
  }

  @Override
  public List<Share> split(SplitContext ctx) {
    SplitStrategy.requirePositive(ctx.totalCents());
    BigInteger weightTotal = BigInteger.ZERO;
    for (SplitInput input : ctx.splits()) {
      if (input.value() < 1 || input.value() > 1_000_000_000_000L) {
        throw new BadRequestException("Each weight must be a positive integer (1..1000000000000)");
      }
      weightTotal = weightTotal.add(BigInteger.valueOf(input.value()));
    }
    LinkedHashMap<UUID, Long> shares = new LinkedHashMap<>();
    long allocated = 0;
    for (SplitInput input : ctx.splits()) {
      long share =
          BigInteger.valueOf(ctx.totalCents())
              .multiply(BigInteger.valueOf(input.value()))
              .divide(weightTotal)
              .longValueExact();
      shares.put(input.userId(), share);
      allocated += share;
    }
    List<UUID> ids = shares.keySet().stream().sorted().toList();
    return SplitStrategy.absorbRemainder(shares, ids, ctx.payerId(), ctx.totalCents() - allocated);
  }
}
