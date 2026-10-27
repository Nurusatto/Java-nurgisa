package com.fixflow.procurement;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.inventory.InventoryService;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcurementService {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final DomainTime time;
  private final InventoryService inventory;

  public ProcurementService(
      Database db, Actors actors, Audit audit, DomainTime time, InventoryService inventory) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.time = time;
    this.inventory = inventory;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    actors.owner();
    UUID supplier = Input.id(body.get("supplier_id"));
    if (!Boolean.TRUE.equals(db.get("supplier", supplier).get("active")))
      throw ApiException.conflict("Поставщик неактивен");
    UUID id =
        db.insert(
            "purchase_order",
            fields(
                "supplier_id",
                supplier,
                "status",
                "DRAFT",
                "expected_at",
                Input.instant(body.get("expected_at")),
                "comment",
                Input.text(body, "comment", 2000, false),
                "created_at",
                time.now(),
                "updated_at",
                time.now()));
    audit.record("PURCHASE_CREATED", "purchase_order", id, "Создана закупка");
    return details(id);
  }

  public Map<String, Object> details(UUID id) {
    actors.owner();
    var purchase = db.get("purchase_order", id);
    purchase.put("items", items(id));
    purchase.put(
        "receipts",
        db.rows(
            "select r.*,i.quantity,i.purchase_order_item_id from purchase_receipt r join"
                + " purchase_receipt_item i on i.receipt_id=r.id where r.purchase_order_id=? order"
                + " by r.created_at",
            id));
    purchase.put(
        "total",
        db.one(
                "select coalesce(sum(round(quantity*unit_price,2)),0) total from"
                    + " purchase_order_item where purchase_order_id=?",
                id)
            .get("total"));
    return purchase;
  }

  public List<Map<String, Object>> items(UUID id) {
    return db.rows(
        "select i.*,p.name part_name,p.sku,coalesce((select sum(r.quantity) from"
            + " purchase_receipt_item r where r.purchase_order_item_id=i.id),0) received_quantity"
            + " from purchase_order_item i join spare_part p on p.id=i.part_id where"
            + " purchase_order_id=? order by i.part_id",
        id);
  }

  @Transactional
  public Map<String, Object> item(UUID purchase, UUID item, Map<String, Object> body) {
    actors.owner();
    var order = db.lock("purchase_order", purchase);
    if (!order.get("status").equals("DRAFT"))
      throw ApiException.conflict("Позиции можно менять только в черновике");
    var values =
        fields(
            "purchase_order_id",
            purchase,
            "part_id",
            Input.id(body.get("part_id")),
            "quantity",
            Input.quantity(body.get("quantity")),
            "unit_price",
            Input.money(body.get("unit_price")));
    if (item == null) item = db.insert("purchase_order_item", values);
    else {
      if (!purchase.equals(db.get("purchase_order_item", item).get("purchase_order_id")))
        throw ApiException.forbidden();
      db.change("purchase_order_item", item, values);
    }
    db.execute(
        "update purchase_order set version=version+1,updated_at=? where id=?",
        time.now(),
        purchase);
    audit.record("PURCHASE_ITEM_CHANGED", "purchase_order", purchase, "Изменена позиция закупки");
    return details(purchase);
  }

  @Transactional
  public Map<String, Object> order(UUID id) {
    actors.owner();
    var order = db.lock("purchase_order", id);
    if (!order.get("status").equals("DRAFT") || items(id).isEmpty())
      throw ApiException.conflict("Подтвердите непустой черновик");
    db.execute(
        "update purchase_order set status='ORDERED',version=version+1,updated_at=? where id=?",
        time.now(),
        id);
    audit.record("PURCHASE_ORDERED", "purchase_order", id, "Закупка подтверждена");
    return details(id);
  }

  @Transactional
  public Map<String, Object> cancel(UUID id) {
    actors.owner();
    var order = db.lock("purchase_order", id);
    if (!Set.of("DRAFT", "ORDERED").contains(order.get("status")))
      throw ApiException.conflict("Нельзя отменить закупку с поступлениями");
    db.execute(
        "update purchase_order set status='CANCELLED',version=version+1,updated_at=? where id=?",
        time.now(),
        id);
    audit.record("PURCHASE_CANCELLED", "purchase_order", id, "Закупка отменена");
    return details(id);
  }

  @Transactional
  public Map<String, Object> receive(UUID id, Map<String, Object> body) {
    actors.owner();
    var order = db.lock("purchase_order", id);
    if (!Set.of("ORDERED", "PARTIALLY_RECEIVED").contains(order.get("status")))
      throw ApiException.conflict("Закупка не ожидает поступления");
    if (!(body.get("items") instanceof List<?> requested) || requested.isEmpty())
      throw ApiException.invalid("Добавьте позиции поступления");
    var available = items(id);
    var plans = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<UUID>();
    for (Object object : requested) {
      if (!(object instanceof Map<?, ?> input)) throw ApiException.invalid("Некорректная позиция");
      UUID itemId = Input.id(input.get("item_id"));
      if (!seen.add(itemId)) throw ApiException.invalid("Позиция повторяется");
      var item =
          available.stream()
              .filter(i -> itemId.equals(i.get("id")))
              .findFirst()
              .orElseThrow(ApiException::forbidden);
      var q = Input.quantity(input.get("quantity"));
      if (q.compareTo(
              Input.decimal(item.get("quantity"))
                  .subtract(Input.decimal(item.get("received_quantity"))))
          > 0) throw ApiException.conflict("Поступление превышает заказанное количество");
      plans.add(fields("item", item, "quantity", q));
    }
    plans.sort(Comparator.comparing(p -> ((Map<?, ?>) p.get("item")).get("part_id").toString()));
    UUID receipt =
        db.insert(
            "purchase_receipt",
            fields(
                "purchase_order_id",
                id,
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    for (var plan : plans) {
      var item = (Map<?, ?>) plan.get("item");
      var movement =
          inventory.movement(
              Input.id(item.get("part_id")),
              "PURCHASE_RECEIPT",
              "IN",
              Input.decimal(plan.get("quantity")),
              Input.decimal(item.get("unit_price")),
              null,
              null,
              "Поступление закупки " + order.get("number"));
      db.insert(
          "purchase_receipt_item",
          fields(
              "receipt_id",
              receipt,
              "purchase_order_item_id",
              item.get("id"),
              "quantity",
              plan.get("quantity"),
              "transaction_id",
              movement.get("id")));
    }
    boolean complete =
        items(id).stream()
            .allMatch(
                i ->
                    Input.decimal(i.get("quantity"))
                            .compareTo(Input.decimal(i.get("received_quantity")))
                        == 0);
    db.execute(
        "update purchase_order set status=?,version=version+1,updated_at=? where id=?",
        complete ? "RECEIVED" : "PARTIALLY_RECEIVED",
        time.now(),
        id);
    audit.record("PURCHASE_RECEIVED", "purchase_order", id, "Зарегистрировано поступление");
    audit.notify(
        actors.current().id(),
        "PURCHASE_RECEIVED",
        null,
        "Получена закупка " + order.get("number"));
    return details(id);
  }
}
