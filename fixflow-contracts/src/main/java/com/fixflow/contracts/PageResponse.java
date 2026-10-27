package com.fixflow.contracts;

import java.util.List;

public record PageResponse<T>(List<T> content, long totalElements, int page, int size) {
  public PageResponse {
    content = List.copyOf(content);
  }
}
