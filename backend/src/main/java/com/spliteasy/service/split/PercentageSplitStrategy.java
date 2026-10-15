package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Each participant owes a typed percentage in basis points (hundredths of a percent); the basis
 * points must sum to 10000. Cents are floored per participant and the payer absorbs the rounding
 * remainder.
 */
@Component
public class PercentageSplitStrategy implements SplitStrategy {

  /** 100% expressed in basis points (hundredths of a percent) — the wire contract for splits. */
  private static final long TOTAL_BASIS_POINTS = 10_000;

  @Override
  public SplitType type() {
    return SplitType.PERCENTAGE;
  }

  @Override
  public List<Share> split(SplitContext ctx) {
    SplitStrategy.requirePositive(ctx.totalCents());
    List<SplitInput> splits = ctx.splits();
    for (SplitInput split : splits) {
      if (split.value() < 0 || split.value() > TOTAL_BASIS_POINTS) {
        throw new BadRequestException(
            "Each percentage must be between 0 and 100% (0..10000 basis points)");
      }
    }
    BigInteger totalBp =
        splits.stream()
            .map(s -> BigInteger.valueOf(s.value()))
            .reduce(BigInteger.ZERO, BigInteger::add);
    if (!totalBp.equals(BigInteger.valueOf(TOTAL_BASIS_POINTS))) {
      throw new BadRequestException(
          "Percentages must add up to 100%% (got %.2f%%)".formatted(totalBp.doubleValue() / 100.0));
    }
    Map<UUID, Long> bpByUser =
        splits.stream().collect(Collectors.toMap(SplitInput::userId, SplitInput::value));
    List<UUID> ids = bpByUser.keySet().stream().sorted().toList();
    LinkedHashMap<UUID, Long> shares = new LinkedHashMap<>();
    long allocated = 0;
    for (UUID id : ids) {
      long bp = bpByUser.get(id);
      // Divide first so even a valid long total cannot overflow during multiplication.
      long share =
          ctx.totalCents() / TOTAL_BASIS_POINTS * bp
              + ctx.totalCents() % TOTAL_BASIS_POINTS * bp / TOTAL_BASIS_POINTS;
      shares.put(id, share);
      allocated += share;
    }
    return SplitStrategy.absorbRemainder(shares, ids, ctx.payerId(), ctx.totalCents() - allocated);
  }
}
