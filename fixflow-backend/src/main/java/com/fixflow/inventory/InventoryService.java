package com.fixflow.inventory;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.repair.RepairService;
import com.fixflow.security.Actors;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final RepairService repairs;
  private final DomainTime time;

  public InventoryService(
      Database db, Actors actors, Audit audit, RepairService repairs, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.repairs = repairs;
    this.time = time;
  }

  @Transactional
  public Map<String, Object> receipt(UUID part, Map<String, Object> body) {
    actors.manager();
    var item = db.lock("spare_part", part);
    return movement(
        part,
        "RECEIPT",
        "IN",
        Input.quantity(body.get("quantity")),
        Input.money(body.getOrDefault("unit_cost", item.get("purchase_price"))),
        null,
        null,
        Input.text(body, "reason", 2000, true));
  }

  @Transactional
  public Map<String, Object> adjust(UUID part, Map<String, Object> body) {
    actors.owner();
    return movement(
        part,
        "ADJUSTMENT",
        Input.choice(body.get("direction"), "IN", "OUT"),
        Input.quantity(body.get("quantity")),
        Input.money(body.getOrDefault("unit_cost", 0)),
        null,
        null,
        Input.text(body, "reason", 2000, true));
  }

  @Transactional
  public Map<String, Object> use(UUID orderId, Map<String, Object> body) {
    var order = repairs.access(orderId, true);
    repairs.editable(order);
    if (!order.get("status").equals("IN_REPAIR"))
      throw ApiException.conflict("Детали используются в статусе IN_REPAIR");
    UUID part = Input.id(body.get("part_id"));
    var item = db.lock("spare_part", part);
    if (!Boolean.TRUE.equals(item.get("active"))) throw ApiException.conflict("Деталь неактивна");
    var quantity = Input.quantity(body.get("quantity"));
    var total = Input.decimal(item.get("quantity"));
    if (total.compareTo(quantity) < 0)
      throw ApiException.conflict("Недостаточно запчастей на складе");
    BigDecimal average =
        Input.decimal(item.get("stock_value")).divide(total, 6, RoundingMode.HALF_UP);
    BigDecimal cost =
        quantity.compareTo(total) == 0
            ? Input.decimal(item.get("stock_value"))
            : Input.rounded(average.multiply(quantity));
    UUID usage =
        db.insert(
            "repair_part_usage",
            fields(
                "order_id",
                orderId,
                "part_id",
                part,
                "quantity",
                quantity,
                "unit_sale_price",
                order.get("source_warranty_id") == null ? item.get("sale_price") : BigDecimal.ZERO,
                "unit_cost",
                average,
                "total_cost",
                cost,
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    movement(
        part, "REPAIR_USE", "OUT", quantity, average, orderId, usage, "Использование в ремонте");
    audit.record("PART_USED", "repair_order", orderId, "Добавлена деталь " + item.get("sku"));
    return db.get("repair_part_usage", usage);
  }

  @Transactional
  public Map<String, Object> returnUsage(UUID orderId, UUID usageId, Map<String, Object> body) {
    actors.manager();
    var order = repairs.access(orderId, true);
    repairs.editable(order);
    var usage = db.lock("repair_part_usage", usageId);
    if (!orderId.equals(usage.get("order_id"))) throw ApiException.forbidden();
    var quantity = Input.quantity(body.get("quantity"));
    var remaining =
        Input.decimal(usage.get("quantity"))
            .subtract(Input.decimal(usage.get("returned_quantity")));
    if (quantity.compareTo(remaining) > 0)
      throw ApiException.conflict("Возврат превышает использованное количество");
    UUID part = Input.id(usage.get("part_id"));
    db.lock("spare_part", part);
    var movement =
        movement(
            part,
            "REPAIR_RETURN",
            "IN",
            quantity,
            Input.decimal(usage.get("unit_cost")),
            orderId,
            usageId,
            Input.text(body, "reason", 2000, true));
    db.execute(
        "update repair_part_usage set returned_quantity=returned_quantity+? where id=?",
        quantity,
        usageId);
    audit.record("PART_RETURNED", "repair_order", orderId, "Оформлен возврат детали");
    return movement;
  }

  @Transactional
  public Map<String, Object> movement(
      UUID part,
      String type,
      String direction,
      BigDecimal quantity,
      BigDecimal incomingCost,
      UUID order,
      UUID usage,
      String reason) {
    var item = db.lock("spare_part", part);
    BigDecimal before = Input.decimal(item.get("quantity")),
        value = Input.decimal(item.get("stock_value"));
    boolean incoming = direction.equals("IN");
    if (!incoming && before.compareTo(quantity) < 0)
      throw ApiException.conflict("Недостаточно запчастей на складе");
    BigDecimal unit = incoming ? incomingCost : value.divide(before, 6, RoundingMode.HALF_UP);
    BigDecimal cost = Input.rounded(unit.multiply(quantity));
    if (!incoming && quantity.compareTo(before) == 0) cost = value;
    if (type.equals("REPAIR_RETURN")) {
      var original = db.get("repair_part_usage", usage);
      var returnedCost =
          Input.decimal(
              db.one(
                      "select coalesce(sum(total_cost),0) n from inventory_transaction where"
                          + " usage_id=? and type='REPAIR_RETURN'",
                      usage)
                  .get("n"));
      var remainingCost = Input.decimal(original.get("total_cost")).subtract(returnedCost);
      cost =
          quantity.compareTo(
                      Input.decimal(original.get("quantity"))
                          .subtract(Input.decimal(original.get("returned_quantity"))))
                  == 0
              ? remainingCost
              : cost.min(remainingCost);
    }
    BigDecimal after = incoming ? before.add(quantity) : before.subtract(quantity),
        afterValue = incoming ? value.add(cost) : value.subtract(cost);
    db.execute(
        "update spare_part set quantity=?,stock_value=?,version=version+1,updated_at=? where id=?",
        after,
        afterValue,
        time.now(),
        part);
    UUID transaction =
        db.insert(
            "inventory_transaction",
            fields(
                "part_id",
                part,
                "type",
                type,
                "direction",
                direction,
                "quantity",
                quantity,
                "unit_cost",
                unit,
                "total_cost",
                cost,
                "after_quantity",
                after,
                "reason",
                reason,
                "actor_id",
                actors.current().id(),
                "order_id",
                order,
                "usage_id",
                usage,
                "created_at",
                time.now()));
    audit.record("INVENTORY_" + type, "spare_part", part, reason);
    if (after.signum() == 0 || after.compareTo(Input.decimal(item.get("minimum_quantity"))) < 0) {
      for (var owner : db.rows("select id from app_user where role='OWNER' and active=true"))
        audit.notify(
            Input.id(owner.get("id")),
            after.signum() == 0 ? "OUT_OF_STOCK" : "LOW_STOCK",
            order,
            "Низкий остаток: " + item.get("name"));
    }
    return db.get("inventory_transaction", transaction);
  }
}
