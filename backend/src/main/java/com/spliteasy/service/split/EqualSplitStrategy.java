package com.spliteasy.service.split;

import com.spliteasy.entity.SplitType;
import com.spliteasy.exception.BadRequestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Divides the total evenly; the payer absorbs the remainder cents. */
@Component
public class EqualSplitStrategy implements SplitStrategy {

    @Override
    public SplitType type() {
        return SplitType.EQUAL;
    }

    @Override
    public List<Share> split(SplitContext ctx) {
        SplitStrategy.requirePositive(ctx.totalCents());
        List<UUID> ids = ctx.participantIds().stream().distinct().sorted().toList();
        if (ids.isEmpty()) {
            throw new BadRequestException("An expense needs at least one participant");
        }
        long base = ctx.totalCents() / ids.size();
        LinkedHashMap<UUID, Long> shares = new LinkedHashMap<>();
        long allocated = 0;
        for (UUID id : ids) {
            shares.put(id, base);
            allocated += base;
        }
        return SplitStrategy.absorbRemainder(shares, ids, ctx.payerId(), ctx.totalCents() - allocated);
    }
}
