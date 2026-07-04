package com.spliteasy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.spliteasy.service.ExpenseSplitCalculator.Share;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests for the pure split math. No Spring, no database — just the
 * rounding rules. The invariant checked everywhere: shares sum to the total,
 * exactly, with no lost or invented cent.
 */
class ExpenseSplitCalculatorTest {

    // Fixed ids in known sorted order so we can assert exactly who absorbs remainder.
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static long sum(List<Share> shares) {
        return shares.stream().mapToLong(Share::shareCents).sum();
    }

    private static long shareOf(List<Share> shares, UUID id) {
        return shares.stream().filter(s -> s.userId().equals(id)).findFirst().orElseThrow().shareCents();
    }

    @Test
    void evenSplitDividesExactly() {
        List<Share> shares = ExpenseSplitCalculator.splitEqually(900, List.of(A, B, C), A);
        assertThat(shares).hasSize(3);
        assertThat(shareOf(shares, A)).isEqualTo(300);
        assertThat(shareOf(shares, B)).isEqualTo(300);
        assertThat(shareOf(shares, C)).isEqualTo(300);
        assertThat(sum(shares)).isEqualTo(900);
    }

    @Test
    void unevenSplitGivesRemainderToPayer() {
        // 1000 / 3 = 333 each, remainder 1 → payer (A) absorbs it.
        List<Share> shares = ExpenseSplitCalculator.splitEqually(1000, List.of(A, B, C), A);
        assertThat(shareOf(shares, A)).isEqualTo(334);
        assertThat(shareOf(shares, B)).isEqualTo(333);
        assertThat(shareOf(shares, C)).isEqualTo(333);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void remainderGoesToPayerEvenWhenPayerIsNotFirstInOrder() {
        // Payer C should absorb the extra cent, not the first-sorted A.
        List<Share> shares = ExpenseSplitCalculator.splitEqually(1000, List.of(A, B, C), C);
        assertThat(shareOf(shares, C)).isEqualTo(334);
        assertThat(shareOf(shares, A)).isEqualTo(333);
        assertThat(shareOf(shares, B)).isEqualTo(333);
        assertThat(sum(shares)).isEqualTo(1000);
    }

    @Test
    void whenPayerIsNotAParticipantFirstParticipantAbsorbs() {
        // Payer C is not among participants A,B → first-sorted (A) absorbs remainder.
        List<Share> shares = ExpenseSplitCalculator.splitEqually(1001, List.of(A, B), C);
        assertThat(shareOf(shares, A)).isEqualTo(501);
        assertThat(shareOf(shares, B)).isEqualTo(500);
        assertThat(sum(shares)).isEqualTo(1001);
    }

    @Test
    void singleParticipantOwesEverything() {
        List<Share> shares = ExpenseSplitCalculator.splitEqually(777, List.of(A), A);
        assertThat(shares).hasSize(1);
        assertThat(shareOf(shares, A)).isEqualTo(777);
    }

    @Test
    void twoParticipantsOddAmount() {
        // 101 / 2 = 50 each, remainder 1 → payer (B) absorbs.
        List<Share> shares = ExpenseSplitCalculator.splitEqually(101, List.of(A, B), B);
        assertThat(shareOf(shares, B)).isEqualTo(51);
        assertThat(shareOf(shares, A)).isEqualTo(50);
        assertThat(sum(shares)).isEqualTo(101);
    }

    @Test
    void duplicateParticipantsAreIgnored() {
        List<Share> shares = ExpenseSplitCalculator.splitEqually(900, List.of(A, A, B, C, B), A);
        assertThat(shares).hasSize(3);
        assertThat(sum(shares)).isEqualTo(900);
    }

    @Test
    void largeGroupNeverLosesOrGainsACent() {
        // 100 participants, prime-ish total that won't divide evenly.
        List<UUID> ids = java.util.stream.Stream.generate(UUID::randomUUID).limit(100).toList();
        List<Share> shares = ExpenseSplitCalculator.splitEqually(100_003, ids, ids.get(0));
        assertThat(sum(shares)).isEqualTo(100_003);
        // Every share is within one cent of the floor.
        long floor = 100_003 / 100;
        assertThat(shares).allSatisfy(s ->
                assertThat(s.shareCents()).isBetween(floor, floor + 3));
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> ExpenseSplitCalculator.splitEqually(0, List.of(A), A))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> ExpenseSplitCalculator.splitEqually(-500, List.of(A, B), A))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyParticipantsIsRejected() {
        assertThatThrownBy(() -> ExpenseSplitCalculator.splitEqually(500, List.of(), A))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
