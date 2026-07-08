package com.spliteasy.service.split;

import com.spliteasy.dto.SplitInput;
import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Each participant owes a typed percentage in basis points (hundredths of a percent);
 * the basis points must sum to 10000. Cents are floored per participant and the payer
 * absorbs the rounding remainder.
 */
@Component
public class PercentageSplitStrategy implements SplitStrategy {

    @Override
    public SplitType type() {
        return SplitType.PERCENTAGE;
    }

    @Override
    public List<Share> split(SplitContext ctx) {
        SplitStrategy.requirePositive(ctx.totalCents());
        List<SplitInput> splits = ctx.splits();
        long totalBp = splits.stream().mapToLong(SplitInput::value).sum();
        if (totalBp != 10_000) {
            throw new BadRequestException(
                    "Percentages must add up to 100%% (got %.2f%%)".formatted(totalBp / 100.0));
        }
        Map<UUID, Long> bpByUser = splits.stream().collect(Collectors.toMap(SplitInput::userId, SplitInput::value));
        List<UUID> ids = bpByUser.keySet().stream().sorted().toList();
        LinkedHashMap<UUID, Long> shares = new LinkedHashMap<>();
        long allocated = 0;
        for (UUID id : ids) {
            long share = ctx.totalCents() * bpByUser.get(id) / 10_000; // floor; all non-negative
            shares.put(id, share);
            allocated += share;
        }
        return SplitStrategy.absorbRemainder(shares, ids, ctx.payerId(), ctx.totalCents() - allocated);
    }
}
