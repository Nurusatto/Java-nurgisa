package com.fixflow.catalog;

import com.fixflow.contracts.PageResponse;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogService catalog;
  private final com.fixflow.common.Idempotency idem;

  public CatalogController(CatalogService catalog, com.fixflow.common.Idempotency idem) {
    this.catalog = catalog;
    this.idem = idem;
  }

  @GetMapping("/{resource:customers|devices|users|technicians|parts|suppliers}")
  public PageResponse<Map<String, Object>> list(
      @PathVariable String resource, @RequestParam Map<String, String> filters) {
    return catalog.list(resource, filters);
  }

  @GetMapping("/{resource:customers|devices|users|parts|suppliers}/{id}")
  public Map<String, Object> get(@PathVariable String resource, @PathVariable UUID id) {
    return catalog.get(resource, id);
  }

  @PostMapping("/{resource:customers|devices|users|parts|suppliers}")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @PathVariable String resource,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return resource.equals("users")
        ? catalog.save(resource, null, body)
        : idem.run("create/" + resource, key, body, () -> catalog.save(resource, null, body));
  }

  @PutMapping("/{resource:customers|devices|users|parts|suppliers}/{id}")
  public Map<String, Object> update(
      @PathVariable String resource, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return catalog.save(resource, id, body);
  }
}
