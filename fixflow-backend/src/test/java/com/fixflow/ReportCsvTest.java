package com.fixflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixflow.report.ReportController;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportCsvTest {
  @Test
  void csvQuotesNewlinesAndCommasAndNeutralizesSpreadsheetFormulas() {
    var row = new LinkedHashMap<String, Object>();
    row.put("name", "=1+1");
    row.put("description", "Comma, quote \" and\nnewline");
    row.put("amount", new java.math.BigDecimal("-25.00"));
    String csv = ReportController.csv(List.of(row));
    assertThat(csv)
        .contains("\"'=1+1\"")
        .contains("\"Comma, quote \"\" and\nnewline\"")
        .contains("\"-25.00\"");
  }
}
