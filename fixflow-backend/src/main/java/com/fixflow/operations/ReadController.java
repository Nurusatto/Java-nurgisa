package com.fixflow.operations;

import com.fixflow.common.*;
import com.fixflow.contracts.PageResponse;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ReadController {
  private final Database db;
  private final Actors actors;

  public ReadController(Database db, Actors actors) {
    this.db = db;
    this.actors = actors;
  }

  @GetMapping(
      "/{resource:inventory-transactions|purchase-orders|payments|refunds|expenses|warranties|notifications|audit-logs}")
  public PageResponse<Map<String, Object>> list(
      @PathVariable String resource, @RequestParam Map<String, String> filters) {
    var actor = actors.current();
    String table =
        switch (resource) {
          case "inventory-transactions" -> "inventory_transaction";
          case "purchase-orders" -> "purchase_order";
          case "payments" -> "payment";
          case "refunds" -> "refund";
          case "expenses" -> "expense";
          case "warranties" -> "warranty";
          case "notifications" -> "notification";
          default -> "audit_log";
        };
    if (Set.of("purchase-orders", "refunds", "expenses", "audit-logs").contains(resource))
      actors.owner();
    else if (!resource.equals("notifications") && !resource.equals("inventory-transactions"))
      actors.manager();
    if (resource.equals("inventory-transactions")) actors.manager();
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>();
    if (resource.equals("notifications")) {
      clauses.add("recipient_id=?");
      args.add(actor.id());
      if (Boolean.parseBoolean(filters.getOrDefault("unread", "false")))
        clauses.add("read_at is null");
    }
    Map<String, Set<String>> permitted =
        Map.of(
            "inventory-transactions",
            Set.of("part_id", "order_id", "type"),
            "purchase-orders",
            Set.of("supplier_id", "status"),
            "payments",
            Set.of("order_id", "method"),
            "warranties",
            Set.of("order_id"),
            "notifications",
            Set.of("type", "order_id"),
            "audit-logs",
            Set.of("action", "entity_type", "entity_id"),
            "expenses",
            Set.of("category", "order_id", "purchase_order_id"),
            "refunds",
            Set.of("payment_id"));
    for (String key : permitted.get(resource))
      if (filters.containsKey(key) && !filters.get(key).isBlank()) {
        clauses.add(key + "=?");
        args.add(key.endsWith("_id") ? Input.id(filters.get(key)) : filters.get(key));
      }
    String timeColumn = resource.equals("audit-logs") ? "occurred_at" : "created_at";
    if (filters.containsKey("from")) {
      clauses.add(timeColumn + ">=?");
      args.add(java.sql.Timestamp.from(Input.instant(filters.get("from"))));
    }
    if (filters.containsKey("to")) {
      clauses.add(timeColumn + "<?");
      args.add(java.sql.Timestamp.from(Input.instant(filters.get("to"))));
    }
    if (filters.containsKey("q") && !filters.get("q").isBlank()) {
      if (resource.equals("warranties") || resource.equals("purchase-orders")) {
        clauses.add("number collate pg_unicode_fast ilike ?");
        args.add("%" + filters.get("q") + "%");
      } else if (resource.equals("notifications")) {
        clauses.add("message collate pg_unicode_fast ilike ?");
        args.add("%" + filters.get("q") + "%");
      } else if (resource.equals("audit-logs") || resource.equals("expenses")) {
        clauses.add("description collate pg_unicode_fast ilike ?");
        args.add("%" + filters.get("q") + "%");
      } else if (resource.equals("payments")) {
        clauses.add(
            "exists(select 1 from repair_order r where r.id=payment.order_id and r.order_number"
                + " collate pg_unicode_fast ilike ?)");
        args.add("%" + filters.get("q") + "%");
      } else if (resource.equals("inventory-transactions")) {
        clauses.add(
            "exists(select 1 from spare_part p where p.id=inventory_transaction.part_id and"
                + " concat_ws(' ',p.sku,p.name) collate pg_unicode_fast ilike ?)");
        args.add("%" + filters.get("q") + "%");
      } else if (resource.equals("refunds")) {
        clauses.add("reason collate pg_unicode_fast ilike ?");
        args.add("%" + filters.get("q") + "%");
      }
    }
    if (resource.equals("warranties")) {
      String status = filters.getOrDefault("status", "");
      switch (status) {
        case "ACTIVE" ->
            clauses.add(
                "revoked_at is null and ends_on>=(current_timestamp at time zone (select timezone"
                    + " from shop_settings))::date");
        case "EXPIRED" ->
            clauses.add(
                "ends_on<(current_timestamp at time zone (select timezone from"
                    + " shop_settings))::date");
        case "EXPIRING" ->
            clauses.add(
                "revoked_at is null and ends_on between (current_timestamp at time zone (select"
                    + " timezone from shop_settings))::date and (current_timestamp at time zone"
                    + " (select timezone from shop_settings))::date+7");
        default -> {}
      }
    }
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    int page = Integer.parseInt(filters.getOrDefault("page", "0")),
        size = Integer.parseInt(filters.getOrDefault("size", "20"));
    if (page < 0 || size < 1 || size > 100) throw ApiException.invalid("Некорректная пагинация");
    long count =
        ((Number) db.one("select count(*) n from " + table + where, args.toArray()).get("n"))
            .longValue();
    args.add(size);
    args.add((long) page * size);
    String columns = "*";
    if (resource.equals("inventory-transactions") && !actor.owner())
      columns =
          "id,part_id,type,direction,quantity,after_quantity,reason,actor_id,order_id,usage_id,created_at";
    if (resource.equals("inventory-transactions"))
      columns +=
          ",(select name from spare_part p where p.id=inventory_transaction.part_id)"
              + " part_name,(select sku from spare_part p where p.id=inventory_transaction.part_id)"
              + " sku";
    if (resource.equals("payments"))
      columns +=
          ",(select order_number from repair_order r where r.id=payment.order_id) order_number";
    if (resource.equals("purchase-orders"))
      columns +=
          ",(select name from supplier s where s.id=purchase_order.supplier_id)"
              + " supplier_name,(select coalesce(sum(round(i.quantity*i.unit_price,2)),0) from"
              + " purchase_order_item i where i.purchase_order_id=purchase_order.id) total";
    if (resource.equals("warranties"))
      columns +=
          ",(select order_number from repair_order r where r.id=warranty.order_id) order_number";
    Set<String> sortFields =
        switch (resource) {
          case "inventory-transactions" -> Set.of("created_at", "quantity", "type", "direction");
          case "purchase-orders" ->
              Set.of("created_at", "number", "status", "expected_at", "total");
          case "payments" -> Set.of("created_at", "amount", "method", "status");
          case "refunds" -> Set.of("created_at", "amount");
          case "expenses" -> Set.of("created_at", "amount", "category");
          case "warranties" -> Set.of("created_at", "number", "starts_on", "ends_on");
          case "notifications" -> Set.of("created_at", "read_at", "type");
          default -> Set.of("occurred_at", "action", "entity_type");
        };
    String sort = filters.getOrDefault("sort", timeColumn);
    if (!sortFields.contains(sort)) throw ApiException.invalid("Недопустимая сортировка");
    String direction = Input.choice(filters.getOrDefault("direction", "desc"), "asc", "desc");
    return new PageResponse<>(
        db.rows(
            "select "
                + columns
                + " from "
                + table
                + where
                + " order by "
                + sort
                + " "
                + direction
                + " nulls last,id limit ? offset ?",
            args.toArray()),
        count,
        page,
        size);
  }

  @GetMapping("/parts/{id}/transactions")
  public PageResponse<Map<String, Object>> movements(
      @PathVariable UUID id, @RequestParam Map<String, String> filters) {
    var copy = new HashMap<>(filters);
    copy.put("part_id", id.toString());
    return list("inventory-transactions", copy);
  }

  @GetMapping("/warranties/{id}")
  public Map<String, Object> warranty(@PathVariable UUID id) {
    actors.manager();
    var warranty = db.get("warranty", id);
    warranty.put(
        "claims",
        db.rows("select * from warranty_claim where warranty_id=? order by created_at", id));
    return warranty;
  }
}
