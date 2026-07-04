package com.spliteasy.service.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spliteasy.dto.SplitInput;
import com.spliteasy.exception.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for UNEQUAL split math — no Spring, no DB. UNEQUAL validation used
 * to live in the service; it now lives in the strategy, so it gets its own unit coverage.
 */
class UnequalSplitStrategyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final UnequalSplitStrategy strategy = new UnequalSplitStrategy();

    private static List<Share> split(long total, SplitInput... splits) {
        return strategy.split(new SplitContext(total, A, null, List.of(splits)));
    }

    private static long shareOf(List<Share> shares, UUID id) {
        return shares.stream().filter(s -> s.userId().equals(id)).findFirst().orElseThrow().shareCents();
    }

    @Test
    void amountsThatSumToTotalAreStoredAsEntered() {
        List<Share> shares = split(1000, new SplitInput(A, 600L), new SplitInput(B, 400L));
        assertThat(shareOf(shares, A)).isEqualTo(600);
        assertThat(shareOf(shares, B)).isEqualTo(400);
    }

    @Test
    void amountsThatDoNotSumToTotalAreRejected() {
        assertThatThrownBy(() -> split(1000, new SplitInput(A, 600L), new SplitInput(B, 300L)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Entered amounts (900c) must add up to the expense total (1000c)");
    }

    @Test
    void aZeroShareIsAllowedAsLongAsTheSumMatches() {
        List<Share> shares = split(1000, new SplitInput(A, 1000L), new SplitInput(B, 0L));
        assertThat(shareOf(shares, A)).isEqualTo(1000);
        assertThat(shareOf(shares, B)).isZero();
    }
}
