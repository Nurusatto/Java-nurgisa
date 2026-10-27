package com.fixflow.desktop.viewmodel;

import java.util.Map;

public final class ListState {
  private int page;
  private String query = "";
  private String status = "";
  private String sort = "";
  private String direction = "desc";

  public int page() {
    return page;
  }

  public void search(String query) {
    this.query = query.trim();
    page = 0;
  }

  public void status(String status) {
    this.status = status;
    page = 0;
  }

  public void next() {
    page++;
  }

  public void previous() {
    page = Math.max(0, page - 1);
  }

  public void sort(String key, String direction) {
    sort = key;
    this.direction = direction;
    page = 0;
  }

  public void reset() {
    page = 0;
    query = "";
    status = "";
    sort = "";
    direction = "desc";
  }

  public Map<String, String> filters() {
    var result =
        new java.util.LinkedHashMap<>(
            Map.of("page", String.valueOf(page), "size", "20", "q", query));
    if (!status.isBlank()) result.put("status", status);
    if (!sort.isBlank()) {
      result.put("sort", sort);
      result.put("direction", direction);
    }
    return result;
  }
}
