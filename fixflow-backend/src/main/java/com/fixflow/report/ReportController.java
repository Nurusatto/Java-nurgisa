package com.fixflow.report;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
  private final ReportService reports;

  public ReportController(ReportService reports) {
    this.reports = reports;
  }

  @GetMapping("/dashboard")
  public Map<String, Object> dashboard(@RequestParam Map<String, String> filters) {
    return reports.dashboard(filters);
  }

  @GetMapping("/{type:repairs|inventory|financial}")
  public Map<String, Object> report(
      @PathVariable String type, @RequestParam Map<String, String> filters) {
    return switch (type) {
      case "repairs" -> reports.repairs(filters);
      case "inventory" -> reports.inventory(filters);
      default -> reports.financial(filters);
    };
  }

  @GetMapping("/{type:repairs|inventory|financial}/csv")
  public ResponseEntity<byte[]> csv(
      @PathVariable String type, @RequestParam Map<String, String> filters) {
    @SuppressWarnings("unchecked")
    var rows =
        (List<Map<String, Object>>)
            report(type, filters).get(type.equals("financial") ? "monthly" : "rows");
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fixflow-" + type + ".csv")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(csv(rows).getBytes(StandardCharsets.UTF_8));
  }

  public static String csv(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return "";
    var keys = new ArrayList<>(rows.getFirst().keySet());
    var result = new StringBuilder("\uFEFF");
    result
        .append(String.join(",", keys.stream().map(ReportController::cell).toList()))
        .append("\r\n");
    for (var row : rows)
      result
          .append(String.join(",", keys.stream().map(key -> cell(row.get(key))).toList()))
          .append("\r\n");
    return result.toString();
  }

  private static String cell(Object value) {
    if (value == null) return "\"\"";
    String text = value.toString();
    if (value instanceof String && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0)
      text = "'" + text;
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }
}
