package com.fixflow.operations;

import com.fixflow.audit.Audit;
import com.fixflow.catalog.CatalogService;
import com.fixflow.common.*;
import com.fixflow.repair.RepairService;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RelatedController {
  private final Database db;
  private final Actors actors;
  private final CatalogService catalog;
  private final RepairService repairs;
  private final Audit audit;

  public RelatedController(
      Database db, Actors actors, CatalogService catalog, RepairService repairs, Audit audit) {
    this.db = db;
    this.actors = actors;
    this.catalog = catalog;
    this.repairs = repairs;
    this.audit = audit;
  }

  @GetMapping("/notification-recipients")
  public Object recipients() {
    actors.manager();
    var rows =
        db.rows("select id,full_name,email,role from app_user where active order by full_name");
    return new com.fixflow.contracts.PageResponse<>(rows, rows.size(), 0, 100);
  }

  @GetMapping("/customers/{id}/devices")
  public Object devices(@PathVariable UUID id, @RequestParam Map<String, String> filters) {
    catalog.get("customers", id);
    var copy = new HashMap<>(filters);
    copy.put("customer_id", id.toString());
    return catalog.list("devices", copy);
  }

  @GetMapping("/customers/{id}/repair-orders")
  public Object orders(@PathVariable UUID id, @RequestParam Map<String, String> filters) {
    catalog.get("customers", id);
    var copy = new HashMap<>(filters);
    copy.put("customer_id", id.toString());
    return repairs.list(copy);
  }

  @GetMapping("/devices/{id}/repair-orders")
  public Object deviceOrders(@PathVariable UUID id, @RequestParam Map<String, String> filters) {
    catalog.get("devices", id);
    var copy = new HashMap<>(filters);
    copy.put("device_id", id.toString());
    return repairs.list(copy);
  }

  @PatchMapping("/users/{id}/status")
  public Object userStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    var user = catalog.get("users", id);
    user.put("active", body.get("active"));
    user.put("version", Input.version(body));
    return catalog.save("users", id, user);
  }

  @PostMapping("/warranties/{id}/revoke")
  @Transactional
  public void revoke(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    actors.owner();
    db.lock("warranty", id);
    String reason = Input.text(body, "reason", 2000, true);
    db.execute("update warranty set revoked_at=current_timestamp,comment=? where id=?", reason, id);
    audit.record("WARRANTY_REVOKED", "warranty", id, "Гарантия отозвана: " + reason);
  }

  @GetMapping("/repair-orders/{id}/documents/{kind:intake|completion|payment}")
  public Map<String, String> document(
      @PathVariable UUID id,
      @PathVariable String kind,
      @RequestParam(required = false) UUID payment_id) {
    actors.manager();
    var order = repairs.details(id);
    var shop = db.one("select * from shop_settings");
    var customer = db.get("customer", Input.id(order.get("customer_id")));
    var device = db.get("device", Input.id(order.get("device_id")));
    if (kind.equals("completion") && order.get("completed_at") == null)
      throw ApiException.conflict("Ремонт ещё не завершён");
    String title =
        switch (kind) {
          case "intake" -> "Квитанция приёма";
          case "completion" -> "Акт выполненных работ";
          default -> "Платёжная квитанция";
        };
    var text =
        new StringBuilder(
            shop.get("name")
                + "\n"
                + shop.get("address")
                + " · "
                + shop.get("phone")
                + "\n\n"
                + title
                + " · "
                + order.get("order_number")
                + "\nКлиент: "
                + customer.get("full_name")
                + "\nТелефон: "
                + customer.get("phone")
                + "\nУстройство: "
                + device.get("manufacturer")
                + " "
                + device.get("model")
                + "\nСерийный номер: "
                + device.get("serial_number")
                + "\n\n");
    if (kind.equals("intake"))
      text.append("Заявленная неисправность: ")
          .append(order.get("problem"))
          .append("\nСостояние: ")
          .append(order.get("intake_condition"))
          .append("\nКомплектность: ")
          .append(order.get("accessories"))
          .append("\nПредварительная стоимость: ")
          .append(order.get("estimated_total"));
    else if (kind.equals("completion")) {
      for (var work :
          db.rows("select name,quantity,unit_price from repair_work where order_id=?", id))
        text.append(work.get("name"))
            .append(" · ")
            .append(work.get("quantity"))
            .append(" × ")
            .append(work.get("unit_price"))
            .append('\n');
      for (var part :
          db.rows(
              "select p.name,u.quantity-u.returned_quantity quantity,u.unit_sale_price from"
                  + " repair_part_usage u join spare_part p on p.id=u.part_id where order_id=?",
              id))
        text.append(part.get("name"))
            .append(" · ")
            .append(part.get("quantity"))
            .append(" × ")
            .append(part.get("unit_sale_price"))
            .append('\n');
      text.append("Итого: ").append(order.get("final_total"));
    } else {
      var payments =
          payment_id == null
              ? db.rows(
                  "select id,amount,method,created_at from payment where order_id=? order by"
                      + " created_at",
                  id)
              : db.rows(
                  "select id,amount,method,created_at from payment where order_id=? and id=?",
                  id,
                  payment_id);
      if (payments.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Платёж не найден");
      for (var payment : payments)
        text.append("Платёж ")
            .append(payment.get("id"))
            .append("\n")
            .append(payment.get("created_at"))
            .append(" · ")
            .append(payment.get("method"))
            .append(" · ")
            .append(payment.get("amount"))
            .append('\n');
    }
    text.append("\nВалюта: ")
        .append(shop.get("currency"))
        .append("\n\nСотрудник: ____________________\nКлиент: ____________________");
    return Map.of("title", title, "content", text.toString());
  }
}
