package com.fixflow.desktop.component;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ExportService {
  private ExportService() {}

  public static CompletableFuture<String> write(Path path, String text) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
            return path.toString();
          } catch (Exception e) {
            throw new CompletionException(new IllegalStateException("Не удалось записать файл", e));
          }
        });
  }

  public static String csv(List<JsonNode> rows, List<String> keys) {
    var text = new StringBuilder("\uFEFF");
    text.append(String.join(",", keys.stream().map(ExportService::cell).toList())).append("\r\n");
    for (var row : rows)
      text.append(
              String.join(
                  ",",
                  keys.stream()
                      .map(key -> cell(row.path(key).isNull() ? "" : row.path(key).asText("")))
                      .toList()))
          .append("\r\n");
    return text.toString();
  }

  private static String cell(String value) {
    if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
