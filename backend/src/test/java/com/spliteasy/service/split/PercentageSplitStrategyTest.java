package com.spliteasy.service.split;

import com.spliteasy.dto.expense.SplitInput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spliteasy.exception.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for PERCENTAGE split math — no Spring, no DB. Assertions carried
 * over verbatim from the pre-refactor ExpenseSplitCalculatorTest (behavior unchanged).
 */
class PercentageSplitStrategyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final PercentageSplitStrategy strategy = new PercentageSplitStrategy();

    private static List<Share> split(long total, UUID payer, SplitInput... splits) {
        return strategy.split(new SplitContext(total, payer, null, List.of(splits)));
    }

    private static long sum(List<Share> shares) {
        return shares.stream().mapToLong(Share::shareCents).sum();
    }

    private static long shareOf(List<Share> shares, UUID id) {
        return shares.stream().filter(s -> s.userId().equals(id)).findFirst().orElseThrow().shareCents();
    }

    @Test
    void percentageEvenSplit() {
        List<Share> shares = split(1000, A, new SplitInput(A, 5000L), new SplitInput(B, 5000L));
        assertThat(shareOf(shares, A)).isEqualTo(500);
        assertThat(shareOf(shares, B)).isEqualTo(500);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void percentageWithRoundingRemainderGoesToPayer() {
        List<Share> shares = split(1000, A,
                new SplitInput(A, 3333L), new SplitInput(B, 3333L), new SplitInput(C, 3334L));
        assertThat(shareOf(shares, A)).isEqualTo(334);
        assertThat(shareOf(shares, B)).isEqualTo(333);
        assertThat(shareOf(shares, C)).isEqualTo(333);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void percentageRemainderFollowsPayerNotFirstId() {
        List<Share> shares = split(1000, C,
                new SplitInput(A, 3333L), new SplitInput(B, 3333L), new SplitInput(C, 3334L));
        assertThat(shareOf(shares, C)).isEqualTo(334);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void percentageNotSummingTo100IsRejected() {
        assertThatThrownBy(() -> split(1000, A, new SplitInput(A, 5000L), new SplitInput(B, 4000L)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void percentageUnevenWeightsStillSumExactly() {
        List<Share> shares = split(999, A, new SplitInput(A, 1000L), new SplitInput(B, 9000L));
        assertThat(sum(shares)).isEqualTo(999);
        assertThat(shareOf(shares, A)).isEqualTo(100);
        assertThat(shareOf(shares, B)).isEqualTo(899);
    }
}
