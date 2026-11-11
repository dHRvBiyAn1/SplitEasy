package com.spliteasy.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HistoryCsvWriterTest {
  @Test
  void neutralisesFormulasEvenBehindLeadingWhitespace() {
    assertThat(HistoryCsvWriter.text("=1+1")).isEqualTo("'=1+1");
    assertThat(HistoryCsvWriter.text("  =HYPERLINK(1)")).isEqualTo("'  =HYPERLINK(1)");
    assertThat(HistoryCsvWriter.text(" \t+1")).isEqualTo("' \t+1");
    assertThat(HistoryCsvWriter.text(" -2")).isEqualTo("' -2");
    assertThat(HistoryCsvWriter.text("\t@cmd")).isEqualTo("'\t@cmd");
  }

  @Test
  void leavesOrdinaryTextAlone() {
    assertThat(HistoryCsvWriter.text(" plain = fine")).isEqualTo(" plain = fine");
    assertThat(HistoryCsvWriter.text("   ")).isEqualTo("   ");
    assertThat(HistoryCsvWriter.text("a,b")).isEqualTo("\"a,b\"");
  }
}
