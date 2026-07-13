package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;

import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
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
        long sum = splits.stream().mapToLong(SplitInput::value).sum();
        if (sum != ctx.totalCents()) {
            throw new BadRequestException(
                    "Entered amounts (%dc) must add up to the expense total (%dc)".formatted(sum, ctx.totalCents()));
        }
        return splits.stream().map(s -> new Share(s.userId(), s.value())).toList();
    }
}
