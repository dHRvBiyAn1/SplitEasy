package com.spliteasy.service;

import com.spliteasy.entity.LedgerCurrency;
import com.spliteasy.repository.HistoryQuery.ExportRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * RFC 4180 rows over the flattened history. Text that a spreadsheet would evaluate as a formula is
 * prefixed with an apostrophe; numeric columns are left untouched.
 */
final class HistoryCsvWriter {
  private static final String HEADER =
      "transaction_id,type,financial_date,group_name,description,currency,amount_minor,amount,"
          + "participant_id,participant_name,paid_minor,owed_minor,settlement_minor,net_minor,net,"
          + "last_changed_by";
  private static final String EOL = "\r\n";

  private final Writer out;

  HistoryCsvWriter(Writer out) {
    this.out = out;
  }

  void header() {
    write(HEADER + EOL);
  }

  void row(ExportRow row) {
    write(
        String.join(
                ",",
                cell(row.transactionId().toString()),
                cell(row.type().name()),
                cell(row.financialDate().toString()),
                text(row.groupName()),
                text(row.description()),
                cell(row.currency().name()),
                cell(Long.toString(row.amountMinor())),
                cell(amount(row.amountMinor(), row.currency())),
                cell(row.participantId().toString()),
                text(row.participantName()),
                cell(Long.toString(row.paidMinor())),
                cell(Long.toString(row.owedMinor())),
                cell(Long.toString(row.settlementMinor())),
                cell(Long.toString(row.netMinor())),
                cell(amount(row.netMinor(), row.currency())),
                text(row.lastChangedBy()))
            + EOL);
  }

  /** Minor units rendered in the currency's own scale: 9000 USD is 90.00, 1000 KWD is 1.000. */
  static String amount(long minor, LedgerCurrency currency) {
    int exponent = currency.minorUnitExponent();
    if (exponent == 0) return Long.toString(minor);
    long unit = (long) Math.pow(10, exponent);
    long absolute = Math.abs(minor);
    return (minor < 0 ? "-" : "")
        + (absolute / unit)
        + "."
        + String.format("%0" + exponent + "d", absolute % unit);
  }

  /** A free-text cell: neutralised against spreadsheet formulas, then quoted when needed. */
  static String text(String value) {
    if (value == null || value.isEmpty()) return "";
    // Spreadsheets skip leading blanks before parsing a formula, so look past them.
    int at = 0;
    while (at < value.length() && (value.charAt(at) == ' ' || value.charAt(at) == '\t')) at++;
    char first = at < value.length() ? value.charAt(at) : ' ';
    String safe =
        (first == '='
                || first == '+'
                || first == '-'
                || first == '@'
                || value.charAt(0) == '\t'
                || value.charAt(0) == '\r')
            ? "'" + value
            : value;
    return quote(safe);
  }

  private static String cell(String value) {
    return quote(value == null ? "" : value);
  }

  private static String quote(String value) {
    if (value.indexOf(',') < 0
        && value.indexOf('"') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0) return value;
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  private void write(String line) {
    try {
      out.write(line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
