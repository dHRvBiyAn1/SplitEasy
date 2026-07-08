package com.spliteasy.service.split;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spliteasy.exception.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for EQUAL split math — no Spring, no DB. Assertions are carried
 * over verbatim from the pre-refactor ExpenseSplitCalculatorTest (behavior unchanged).
 */
class EqualSplitStrategyTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final EqualSplitStrategy strategy = new EqualSplitStrategy();

    private static List<Share> split(long total, List<UUID> ids, UUID payer) {
        return strategy.split(new SplitContext(total, payer, ids, null));
    }

    private static long sum(List<Share> shares) {
        return shares.stream().mapToLong(Share::shareCents).sum();
    }

    private static long shareOf(List<Share> shares, UUID id) {
        return shares.stream().filter(s -> s.userId().equals(id)).findFirst().orElseThrow().shareCents();
    }

    @Test
    void evenSplitDividesExactly() {
        List<Share> shares = split(900, List.of(A, B, C), A);
        assertThat(shares).hasSize(3);
        assertThat(shareOf(shares, A)).isEqualTo(300);
        assertThat(shareOf(shares, B)).isEqualTo(300);
        assertThat(shareOf(shares, C)).isEqualTo(300);
        assertThat(sum(shares)).isEqualTo(900);
    }

    @Test
    void unevenSplitGivesRemainderToPayer() {
        List<Share> shares = split(1000, List.of(A, B, C), A);
        assertThat(shareOf(shares, A)).isEqualTo(334);
        assertThat(shareOf(shares, B)).isEqualTo(333);
        assertThat(shareOf(shares, C)).isEqualTo(333);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void remainderGoesToPayerEvenWhenPayerIsNotFirstInOrder() {
        List<Share> shares = split(1000, List.of(A, B, C), C);
        assertThat(shareOf(shares, C)).isEqualTo(334);
        assertThat(shareOf(shares, A)).isEqualTo(333);
        assertThat(shareOf(shares, B)).isEqualTo(333);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void whenPayerIsNotAParticipantFirstParticipantAbsorbs() {
        List<Share> shares = split(1001, List.of(A, B), C);
        assertThat(shareOf(shares, A)).isEqualTo(501);
        assertThat(shareOf(shares, B)).isEqualTo(500);
        assertThat(sum(shares)).isEqualTo(1001);
    }

    @Test
    void singleParticipantOwesEverything() {
        List<Share> shares = split(777, List.of(A), A);
        assertThat(shares).hasSize(1);
        assertThat(shareOf(shares, A)).isEqualTo(777);
    }

    @Test
    void twoParticipantsOddAmount() {
        List<Share> shares = split(101, List.of(A, B), B);
        assertThat(shareOf(shares, B)).isEqualTo(51);
        assertThat(shareOf(shares, A)).isEqualTo(50);
        assertThat(sum(shares)).isEqualTo(101);
    }

    @Test
    void duplicateParticipantsAreIgnored() {
        List<Share> shares = split(900, List.of(A, A, B, C, B), A);
        assertThat(shares).hasSize(3);
        assertThat(sum(shares)).isEqualTo(900);
    }

    @Test
    void largeGroupNeverLosesOrGainsACent() {
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(100).toList();
        List<Share> shares = split(100_003, ids, ids.get(0));
        assertThat(sum(shares)).isEqualTo(100_003);
        long floor = 100_003 / 100;
        assertThat(shares).allSatisfy(s -> assertThat(s.shareCents()).isBetween(floor, floor + 3));
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> split(0, List.of(A), A)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> split(-500, List.of(A, B), A)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void emptyParticipantsIsRejected() {
        assertThatThrownBy(() -> split(500, List.of(), A)).isInstanceOf(BadRequestException.class);
    }
}
