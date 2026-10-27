package com.fixflow.operations;

import com.fixflow.common.*;
import com.fixflow.finance.FinanceService;
import com.fixflow.inventory.InventoryService;
import com.fixflow.procurement.ProcurementService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
  private final Idempotency idem;
  private final InventoryService inventory;
  private final FinanceService finance;
  private final ProcurementService purchases;

  public OperationsController(
      Idempotency idem,
      InventoryService inventory,
      FinanceService finance,
      ProcurementService purchases) {
    this.idem = idem;
    this.inventory = inventory;
    this.finance = finance;
    this.purchases = purchases;
  }

  @PostMapping("/parts/{id}/{action:receipts|adjustments}")
  public Map<String, Object> stock(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run(
        "part/" + id + "/" + action,
        key,
        body,
        () -> action.equals("receipts") ? inventory.receipt(id, body) : inventory.adjust(id, body));
  }

  @PostMapping("/repair-orders/{id}/parts")
  public Map<String, Object> use(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("order/" + id + "/parts", key, body, () -> inventory.use(id, body));
  }

  @PostMapping("/repair-orders/{id}/parts/{usage}/return")
  public Map<String, Object> returnPart(
      @PathVariable UUID id,
      @PathVariable UUID usage,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run(
        "usage/" + usage + "/return", key, body, () -> inventory.returnUsage(id, usage, body));
  }

  @PostMapping("/repair-orders/{id}/{action:complete|deliver|payments|adjustments|cancel}")
  public Map<String, Object> money(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run(
        "order/" + id + "/" + action,
        key,
        body,
        () ->
            switch (action) {
              case "complete" -> finance.complete(id, body);
              case "deliver" -> finance.deliver(id, body);
              case "payments" -> finance.payment(id, body);
              case "adjustments" -> finance.adjustment(id, body);
              default -> finance.cancel(id, body);
            });
  }

  @GetMapping("/repair-orders/{id}/balance")
  public Map<String, Object> balance(@PathVariable UUID id) {
    return finance.balance(id);
  }

  @PostMapping("/payments/{id}/refunds")
  public Map<String, Object> refund(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("payment/" + id + "/refund", key, body, () -> finance.refund(id, body));
  }

  @PostMapping("/expenses")
  public Map<String, Object> expense(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
    return idem.run("expense", key, body, () -> finance.expense(body));
  }

  @PostMapping("/purchase-orders")
  public Map<String, Object> purchase(
      @RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
    return idem.run("purchase", key, body, () -> purchases.create(body));
  }

  @GetMapping("/purchase-orders/{id}")
  public Map<String, Object> purchaseDetails(@PathVariable UUID id) {
    return purchases.details(id);
  }

  @PostMapping("/purchase-orders/{id}/items")
  public Map<String, Object> item(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return purchases.item(id, null, body);
  }

  @PutMapping("/purchase-orders/{id}/items/{item}")
  public Map<String, Object> itemEdit(
      @PathVariable UUID id, @PathVariable UUID item, @RequestBody Map<String, Object> body) {
    return purchases.item(id, item, body);
  }

  @PostMapping("/purchase-orders/{id}/{action:order|cancel|receive}")
  public Map<String, Object> purchaseAction(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run(
        "purchase/" + id + "/" + action,
        key,
        body,
        () ->
            switch (action) {
              case "order" -> purchases.order(id);
              case "cancel" -> purchases.cancel(id);
              default -> purchases.receive(id, body);
            });
  }

  @PostMapping("/warranties/{id}/claims")
  public Map<String, Object> claim(
      @PathVariable UUID id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody Map<String, Object> body) {
    return idem.run("warranty/" + id + "/claim", key, body, () -> finance.warrantyClaim(id, body));
  }
}
