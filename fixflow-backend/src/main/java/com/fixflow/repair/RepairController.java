package com.fixflow.repair;

import com.fixflow.contracts.PageResponse;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/repair-orders")
public class RepairController {
  private final RepairService repairs;
  private final com.fixflow.common.Idempotency idem;

  public RepairController(RepairService repairs, com.fixflow.common.Idempotency idem) {
    this.repairs = repairs;
    this.idem = idem;
  }

  @GetMapping
  public PageResponse<Map<String, Object>> list(@RequestParam Map<String, String> filters) {
    return repairs.list(filters);
  }

  @GetMapping("/{id}")
  public Map<String, Object> details(@PathVariable UUID id) {
    return repairs.details(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
    return idem.run("create/repair-order", key, body, () -> repairs.create(body));
  }

  @PutMapping("/{id}")
  public Map<String, Object> edit(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return repairs.edit(id, body);
  }

  @PostMapping("/{id}/assign")
  public Map<String, Object> assign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return repairs.assign(id, body);
  }

  @PostMapping("/{id}/status")
  public Map<String, Object> status(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return repairs.status(id, body);
  }

  @GetMapping("/{id}/history")
  public Object history(@PathVariable UUID id) {
    return repairs.details(id).get("history");
  }

  @PostMapping("/{id}/diagnostics")
  public Map<String, Object> diagnostic(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("order/" + id + "/diagnostic", key, body, () -> repairs.diagnostic(id, body));
  }

  @PostMapping("/{id}/works")
  public Map<String, Object> work(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("order/" + id + "/work", key, body, () -> repairs.work(id, body));
  }

  @PutMapping("/{id}/works/{work}/price")
  public Map<String, Object> price(
      @PathVariable UUID id, @PathVariable UUID work, @RequestBody Map<String, Object> body) {
    return repairs.priceWork(id, work, body);
  }

  @PostMapping("/{id}/approvals")
  public Map<String, Object> approve(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("order/" + id + "/approval", key, body, () -> repairs.approve(id, body));
  }
}
