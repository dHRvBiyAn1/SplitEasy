package com.spliteasy.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurrenceCalendarTest {
  @Test
  void monthlyKeepsTheAnchorDayAndClampsWithoutDrifting() {
    var anchor = LocalDate.of(2027, 1, 31);
    var d = anchor;
    var seen = new java.util.ArrayList<LocalDate>();
    for (int i = 0; i < 4; i++) {
      d = RecurrenceCalendar.next(Frequency.MONTHLY, anchor, d);
      seen.add(d);
    }
    assertThat(seen)
        .containsExactly(
            LocalDate.of(2027, 2, 28),
            LocalDate.of(2027, 3, 31),
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 31));
    assertThat(
            RecurrenceCalendar.next(
                Frequency.MONTHLY, LocalDate.of(2027, 1, 31), LocalDate.of(2028, 1, 31)))
        .isEqualTo(LocalDate.of(2028, 2, 29));
  }

  @Test
  void weeklyAndDailyStep() {
    var a = LocalDate.of(2026, 10, 31);
    assertThat(RecurrenceCalendar.next(Frequency.WEEKLY, a, a)).isEqualTo(a.plusDays(7));
    assertThat(RecurrenceCalendar.next(Frequency.DAILY, a, a)).isEqualTo(a.plusDays(1));
  }

  @Test
  void firstOnOrAfterFindsTheNextAnchoredDate() {
    var a = LocalDate.of(2026, 1, 31);
    assertThat(RecurrenceCalendar.firstOnOrAfter(Frequency.MONTHLY, a, LocalDate.of(2026, 2, 1)))
        .isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(RecurrenceCalendar.firstOnOrAfter(Frequency.DAILY, a, a.minusDays(5))).isEqualTo(a);
  }
}
