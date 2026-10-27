package com.fixflow.finance;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.repair.*;
import com.fixflow.security.Actors;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceService {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final RepairService repairs;
  private final DomainTime time;
  private final RequestValidation validation;

  public FinanceService(
      Database db,
      Actors actors,
      Audit audit,
      RepairService repairs,
      DomainTime time,
      RequestValidation validation) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.repairs = repairs;
    this.time = time;
    this.validation = validation;
  }

  public Map<String, Object> balance(UUID id) {
    repairs.access(id, false);
    var row =
        db.one(
            "select coalesce(r.final_total,0)+(select coalesce(sum(a.amount),0) from"
                + " order_price_adjustment a where a.order_id=r.id) charged,(select"
                + " coalesce(sum(p.amount),0) from payment p where p.order_id=r.id)-(select"
                + " coalesce(sum(f.amount),0) from refund f join payment p on p.id=f.payment_id"
                + " where p.order_id=r.id) paid from repair_order r where r.id=?",
            id);
    var delta = Input.decimal(row.get("charged")).subtract(Input.decimal(row.get("paid")));
    row.put("debt", delta.max(BigDecimal.ZERO));
    row.put("credit", delta.negate().max(BigDecimal.ZERO));
    return row;
  }

  @Transactional
  public Map<String, Object> complete(UUID id, Map<String, Object> body) {
    var order = repairs.access(id, true);
    repairs.version(order, body);
    RepairPolicy.check(order.get("status").toString(), "COMPLETED", actors.current().role());
    if (db.rows("select id from diagnostic_record where order_id=?", id).isEmpty()
        || db.rows("select id from repair_work where order_id=?", id).isEmpty())
      throw ApiException.conflict("Нужны диагностика и выполненные работы");
    if (!db.rows("select id from repair_work where order_id=? and not price_confirmed", id)
        .isEmpty()) throw ApiException.conflict("Менеджер должен подтвердить стоимость всех работ");
    var works =
        Input.decimal(
            db.one(
                    "select coalesce(sum(round(quantity*unit_price,2)),0) total from repair_work"
                        + " where order_id=?",
                    id)
                .get("total"));
    var parts =
        Input.decimal(
            db.one(
                    "select coalesce(sum(round((quantity-returned_quantity)*unit_sale_price,2)),0)"
                        + " total from repair_part_usage where order_id=?",
                    id)
                .get("total"));
    var total = works.add(parts);
    if (db.rows("select id from repair_approval where order_id=? and amount>=?", id, total)
        .isEmpty()) throw ApiException.conflict("Итоговая цена не согласована с клиентом");
    db.execute(
        "update repair_order set final_total=?,completed_at=? where id=?", total, time.now(), id);
    repairs.move(order, "COMPLETED", "Работы завершены и проверены");
    return balance(id);
  }

  @Transactional
  public Map<String, Object> payment(UUID id, Map<String, Object> body) {
    validation.check(body, com.fixflow.contracts.PaymentInput.class);
    actors.manager();
    var order = repairs.access(id, true);
    if (order.get("final_total") == null || order.get("status").equals("CANCELLED"))
      throw ApiException.conflict("Сначала завершите ремонт и зафиксируйте итог");
    var amount = Input.money(body.get("amount"));
    if (amount.signum() == 0) throw ApiException.invalid("Оплата должна быть положительной");
    if (amount.compareTo(Input.decimal(balance(id).get("debt"))) > 0)
      throw ApiException.conflict("Сумма превышает задолженность");
    UUID payment =
        db.insert(
            "payment",
            fields(
                "order_id",
                id,
                "amount",
                amount,
                "method",
                Input.choice(body.get("method"), "CASH", "CARD", "BANK_TRANSFER", "OTHER"),
                "comment",
                Input.text(body, "comment", 2000, false),
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    audit.record("PAYMENT_POSTED", "repair_order", id, "Зарегистрирована оплата " + amount);
    return db.get("payment", payment);
  }

  @Transactional
  public Map<String, Object> refund(UUID paymentId, Map<String, Object> body) {
    actors.owner();
    var payment = db.get("payment", paymentId);
    UUID order = Input.id(payment.get("order_id"));
    repairs.access(order, true);
    db.lock("payment", paymentId);
    var amount = Input.money(body.get("amount"));
    if (amount.signum() == 0) throw ApiException.invalid("Возврат должен быть положительным");
    var refunded =
        Input.decimal(
            db.one("select coalesce(sum(amount),0) total from refund where payment_id=?", paymentId)
                .get("total"));
    if (amount.add(refunded).compareTo(Input.decimal(payment.get("amount"))) > 0)
      throw ApiException.conflict("Возврат превышает исходную оплату");
    UUID id =
        db.insert(
            "refund",
            fields(
                "payment_id",
                paymentId,
                "amount",
                amount,
                "reason",
                Input.text(body, "reason", 2000, true),
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    audit.record("REFUND_POSTED", "repair_order", order, "Оформлен возврат " + amount);
    return db.get("refund", id);
  }

  @Transactional
  public Map<String, Object> adjustment(UUID id, Map<String, Object> body) {
    actors.owner();
    var order = repairs.access(id, true);
    if (order.get("final_total") == null) throw ApiException.conflict("Итог ещё не зафиксирован");
    BigDecimal amount = new BigDecimal(body.get("amount").toString());
    if (amount.signum() == 0 || amount.scale() > 2 || amount.precision() > 18)
      throw ApiException.invalid("Некорректная сумма корректировки");
    if (Input.decimal(balance(id).get("charged")).add(amount).signum() < 0)
      throw ApiException.conflict("Начисление не может быть отрицательным");
    UUID adjustment =
        db.insert(
            "order_price_adjustment",
            fields(
                "order_id",
                id,
                "amount",
                amount,
                "reason",
                Input.text(body, "reason", 2000, true),
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    audit.record("PRICE_ADJUSTED", "repair_order", id, "Проведена корректировка " + amount);
    return db.get("order_price_adjustment", adjustment);
  }

  @Transactional
  public Map<String, Object> deliver(UUID id, Map<String, Object> body) {
    actors.manager();
    var order = repairs.access(id, true);
    repairs.version(order, body);
    RepairPolicy.check(order.get("status").toString(), "DELIVERED", actors.current().role());
    var balance = balance(id);
    if (Input.decimal(balance.get("debt")).signum() > 0
        || Input.decimal(balance.get("credit")).signum() > 0)
      throw ApiException.conflict("Перед выдачей урегулируйте задолженность и возвраты");
    db.execute("update repair_order set delivered_at=? where id=?", time.now(), id);
    repairs.move(order, "DELIVERED", "Устройство выдано клиенту");
    var settings = db.one("select * from shop_settings");
    int days = ((Number) settings.get("warranty_days")).intValue();
    if (days > 0) {
      LocalDate start =
          time.now().atZone(ZoneId.of(settings.get("timezone").toString())).toLocalDate();
      UUID warranty =
          db.insert(
              "warranty",
              fields(
                  "order_id",
                  id,
                  "starts_on",
                  start,
                  "ends_on",
                  start.plusDays(days),
                  "terms",
                  "Гарантия на выполненные работы и установленные детали при соблюдении условий"
                      + " эксплуатации",
                  "created_at",
                  time.now()));
      audit.record("WARRANTY_CREATED", "warranty", warranty, "Создана гарантия при выдаче");
      audit.notify(actors.current().id(), "WARRANTY_CREATED", id, "Создана гарантия");
    }
    return repairs.details(id);
  }

  @Transactional
  public Map<String, Object> cancel(UUID id, Map<String, Object> body) {
    actors.manager();
    var order = repairs.access(id, true);
    repairs.version(order, body);
    RepairPolicy.check(order.get("status").toString(), "CANCELLED", actors.current().role());
    if (Input.decimal(balance(id).get("paid")).signum() != 0)
      throw ApiException.conflict("Сначала оформите возврат денег");
    boolean hasParts =
        !db.rows(
                "select id from repair_part_usage where order_id=? and quantity>returned_quantity",
                id)
            .isEmpty();
    if (hasParts
        && !(actors.current().owner()
            && Boolean.parseBoolean(String.valueOf(body.get("keep_used_parts")))))
      throw ApiException.conflict(
          "Верните пригодные детали или получите подтверждение владельца о невозвратном"
              + " использовании");
    db.execute("update repair_order set final_total=0 where id=?", id);
    repairs.move(order, "CANCELLED", Input.text(body, "reason", 2000, true));
    return repairs.details(id);
  }

  @Transactional
  public Map<String, Object> expense(Map<String, Object> body) {
    actors.owner();
    var amount = Input.money(body.get("amount"));
    if (amount.signum() == 0) throw ApiException.invalid("Расход должен быть положительным");
    UUID id =
        db.insert(
            "expense",
            fields(
                "amount",
                amount,
                "category",
                Input.choice(
                    body.get("category"),
                    "PARTS_PURCHASE",
                    "RENT",
                    "UTILITIES",
                    "TOOLS",
                    "MAINTENANCE",
                    "OTHER"),
                "description",
                Input.text(body, "description", 2000, true),
                "order_id",
                body.get("order_id") == null ? null : Input.id(body.get("order_id")),
                "purchase_order_id",
                body.get("purchase_order_id") == null
                    ? null
                    : Input.id(body.get("purchase_order_id")),
                "actor_id",
                actors.current().id(),
                "created_at",
                time.now()));
    audit.record("EXPENSE_POSTED", "expense", id, "Зарегистрирован расход");
    return db.get("expense", id);
  }

  @Transactional
  public Map<String, Object> warrantyClaim(UUID warrantyId, Map<String, Object> body) {
    actors.manager();
    var warranty = db.lock("warranty", warrantyId);
    LocalDate today =
        time.now()
            .atZone(
                ZoneId.of(db.one("select timezone from shop_settings").get("timezone").toString()))
            .toLocalDate();
    if (warranty.get("revoked_at") != null
        || today.isAfter(LocalDate.parse(warranty.get("ends_on").toString())))
      throw ApiException.conflict("Гарантия не действует");
    var original = db.get("repair_order", Input.id(warranty.get("order_id")));
    Object technician = body.getOrDefault("technician_id", original.get("technician_id"));
    if (technician != null && !body.containsKey("technician_id")) {
      var previous = db.get("app_user", Input.id(technician));
      if (!Boolean.TRUE.equals(previous.get("active"))
          || !"TECHNICIAN".equals(previous.get("role"))) technician = null;
    }
    var order =
        repairs.create(
            fields(
                "customer_id",
                original.get("customer_id"),
                "device_id",
                original.get("device_id"),
                "technician_id",
                technician,
                "problem",
                Input.text(body, "description", 4000, true),
                "estimated_total",
                0));
    UUID followup = Input.id(order.get("id"));
    db.execute("update repair_order set source_warranty_id=? where id=?", warrantyId, followup);
    db.insert(
        "warranty_claim",
        fields(
            "warranty_id",
            warrantyId,
            "followup_order_id",
            followup,
            "description",
            Input.text(body, "description", 4000, true),
            "created_at",
            time.now()));
    audit.record("WARRANTY_CLAIM", "warranty", warrantyId, "Создан гарантийный заказ");
    return repairs.details(followup);
  }
}
