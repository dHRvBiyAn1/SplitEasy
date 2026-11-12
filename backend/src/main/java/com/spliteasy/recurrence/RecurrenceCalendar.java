package com.spliteasy.recurrence;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Local-date arithmetic for schedules; the zone only decides which date "today" is. */
final class RecurrenceCalendar {
  private RecurrenceCalendar() {}

  /** The occurrence after {@code current}; monthly always counts from the anchor, never drifts. */
  static LocalDate next(Frequency frequency, LocalDate anchor, LocalDate current) {
    return switch (frequency) {
      case DAILY -> current.plusDays(1);
      case WEEKLY -> current.plusWeeks(1);
      case MONTHLY ->
          anchor.plusMonths(
              ChronoUnit.MONTHS.between(anchor.withDayOfMonth(1), current.withDayOfMonth(1)) + 1);
    };
  }

  static LocalDate firstOnOrAfter(Frequency frequency, LocalDate anchor, LocalDate date) {
    var d = anchor;
    while (d.isBefore(date)) d = next(frequency, anchor, d);
    return d;
  }
}
