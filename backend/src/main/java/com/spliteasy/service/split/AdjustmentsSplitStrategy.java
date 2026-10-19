package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Signed cents added to the existing deterministic equal allocation over the listed participants.
 */
@Component
public class AdjustmentsSplitStrategy implements SplitStrategy {
  private final EqualSplitStrategy equal;

  public AdjustmentsSplitStrategy(EqualSplitStrategy equal) {
    this.equal = equal;
  }

  @Override
  public SplitType type() {
    return SplitType.ADJUSTMENTS;
  }

  @Override
  public List<Share> split(SplitContext ctx) {
    BigInteger sum = BigInteger.ZERO;
    for (SplitInput input : ctx.splits()) {
      if (input.value() < -1_000_000_000_000L || input.value() > 1_000_000_000_000L) {
        throw new BadRequestException(
            "Each adjustment must be between -1000000000000 and 1000000000000 cents");
      }
      sum = sum.add(BigInteger.valueOf(input.value()));
    }
    if (sum.signum() != 0) {
      throw new BadRequestException("Adjustments must add up to zero cents");
    }
    Map<UUID, Long> adjustments =
        ctx.splits().stream().collect(Collectors.toMap(SplitInput::userId, SplitInput::value));
    List<Share> base =
        equal.split(
            SplitContext.forEqual(
                ctx.totalCents(),
                ctx.payerId(),
                ctx.splits().stream().map(SplitInput::userId).toList()));
    return base.stream()
        .map(
            share -> {
              BigInteger finalShare =
                  BigInteger.valueOf(share.shareCents())
                      .add(BigInteger.valueOf(adjustments.get(share.userId())));
              if (finalShare.signum() < 0
                  || finalShare.compareTo(BigInteger.valueOf(ctx.totalCents())) > 0) {
                throw new BadRequestException(
                    "Adjustments must leave every final share nonnegative and within the expense total");
              }
              return new Share(share.userId(), finalShare.longValueExact());
            })
        .toList();
  }
}
