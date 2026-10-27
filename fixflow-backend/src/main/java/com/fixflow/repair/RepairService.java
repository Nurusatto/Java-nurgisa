package com.fixflow.repair;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.contracts.PageResponse;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairService {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final DomainTime time;

  public RepairService(Database db, Actors actors, Audit audit, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.time = time;
  }

  public Map<String, Object> access(UUID id, boolean lock) {
    var order = lock ? db.lock("repair_order", id) : db.get("repair_order", id);
    var actor = actors.current();
    if (actor.technician() && !actor.id().equals(order.get("technician_id")))
      throw ApiException.forbidden();
    return order;
  }

  public void editable(Map<String, Object> order) {
    if (RepairPolicy.frozen(order.get("status").toString()))
      throw ApiException.conflict("Заказ закрыт для изменений");
  }

  public PageResponse<Map<String, Object>> list(Map<String, String> filters) {
    var actor = actors.current();
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>();
    if (actor.technician()) {
      clauses.add("r.technician_id=?");
      args.add(actor.id());
    }
    String q = filters.getOrDefault("q", "");
    if (q.length() > 200) throw ApiException.invalid("Поиск слишком длинный");
    if (!q.isBlank()) {
      clauses.add(
          "concat_ws(' ',r.order_number,c.full_name,d.model) collate pg_unicode_fast ilike ?");
      args.add("%" + q + "%");
    }
    for (String key : List.of("status", "priority"))
      if (filters.containsKey(key) && !filters.get(key).isBlank()) {
        clauses.add("r." + key + "=?");
        args.add(filters.get(key));
      }
    for (String key : List.of("technician_id", "customer_id", "device_id"))
      if (filters.containsKey(key)) {
        clauses.add("r." + key + "=?");
        args.add(Input.id(filters.get(key)));
      }
    if (Boolean.parseBoolean(filters.getOrDefault("overdue", "false")))
      clauses.add(
          "r.due_at<current_timestamp and r.status not in"
              + " ('COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')");
    if (filters.containsKey("from")) {
      clauses.add("r.created_at>=?");
      args.add(java.sql.Timestamp.from(Input.instant(filters.get("from"))));
    }
    if (filters.containsKey("to")) {
      clauses.add("r.created_at<?");
      args.add(java.sql.Timestamp.from(Input.instant(filters.get("to"))));
    }
    String from =
        " from repair_order r join customer c on c.id=r.customer_id join device d on"
            + " d.id=r.device_id left join app_user u on u.id=r.technician_id";
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    int page = Integer.parseInt(filters.getOrDefault("page", "0")),
        size = Integer.parseInt(filters.getOrDefault("size", "20"));
    if (page < 0 || size < 1 || size > 100) throw ApiException.invalid("Некорректная пагинация");
    String sort = filters.getOrDefault("sort", "created_at");
    if (!Set.of("created_at", "due_at", "status", "priority", "order_number").contains(sort))
      throw ApiException.invalid("Недопустимая сортировка");
    String direction = Input.choice(filters.getOrDefault("direction", "desc"), "asc", "desc");
    long count =
        ((Number) db.one("select count(*) n" + from + where, args.toArray()).get("n")).longValue();
    args.add(size);
    args.add((long) page * size);
    return new PageResponse<>(
        db.rows(
            "select r.*,c.full_name customer_name,d.model device_model,u.full_name technician_name"
                + from
                + where
                + " order by r."
                + sort
                + " "
                + direction
                + ",r.id limit ? offset ?",
            args.toArray()),
        count,
        page,
        size);
  }

  public Map<String, Object> details(UUID id) {
    var order = access(id, false);
    order.put("customer", db.get("customer", Input.id(order.get("customer_id"))));
    if (actors.current().technician()) {
      ((Map<?, ?>) order.get("customer")).clear();
      order.put(
          "customer",
          Map.of(
              "full_name",
              db.get("customer", Input.id(order.get("customer_id"))).get("full_name")));
    }
    order.put("device", db.get("device", Input.id(order.get("device_id"))));
    order.put(
        "history",
        db.rows(
            "select h.*,u.full_name actor_name from repair_status_history h join app_user u on"
                + " u.id=h.actor_id where order_id=? order by occurred_at,id",
            id));
    order.put(
        "diagnostics",
        db.rows(
            "select d.*,u.full_name author_name from diagnostic_record d join app_user u on"
                + " u.id=d.author_id where order_id=? order by created_at",
            id));
    order.put(
        "works", db.rows("select * from repair_work where order_id=? order by created_at", id));
    order.put(
        "approvals",
        db.rows("select * from repair_approval where order_id=? order by created_at", id));
    var usages =
        db.rows(
            "select u.*,p.name part_name,p.sku from repair_part_usage u join spare_part p on"
                + " p.id=u.part_id where order_id=? order by created_at",
            id);
    if (!actors.current().owner())
      usages.forEach(
          u -> {
            u.remove("unit_cost");
            u.remove("total_cost");
          });
    order.put("parts", usages);
    order.put("warranties", db.rows("select * from warranty where order_id=?", id));
    if (!actors.current().technician()) {
      order.put(
          "payments",
          db.rows(
              "select p.*,coalesce((select sum(f.amount) from refund f where f.payment_id=p.id),0)"
                  + " refunded from payment p where p.order_id=? order by p.created_at",
              id));
      order.put(
          "refunds",
          db.rows(
              "select f.* from refund f join payment p on p.id=f.payment_id where p.order_id=?"
                  + " order by f.created_at",
              id));
      order.put(
          "adjustments",
          db.rows("select * from order_price_adjustment where order_id=? order by created_at", id));
      var totals =
          db.one(
              "select coalesce((select sum(amount) from payment where"
                  + " order_id=?),0)-coalesce((select sum(f.amount) from refund f join payment p on"
                  + " p.id=f.payment_id where p.order_id=?),0) paid,coalesce((select sum(amount)"
                  + " from order_price_adjustment where order_id=?),0) adjustments",
              id,
              id,
              id);
      var charged =
          Input.decimal(order.get("final_total")).add(Input.decimal(totals.get("adjustments")));
      var delta = charged.subtract(Input.decimal(totals.get("paid")));
      totals.put("charged", charged);
      totals.put("debt", delta.max(java.math.BigDecimal.ZERO));
      totals.put("credit", delta.negate().max(java.math.BigDecimal.ZERO));
      order.put("balance", totals);
    }
    order.put(
        "allowed_statuses",
        RepairPolicy.next(order.get("status").toString()).stream()
            .filter(s -> !Set.of("COMPLETED", "DELIVERED", "CANCELLED").contains(s))
            .filter(s -> !actors.current().technician() || !s.equals("READY_FOR_PICKUP"))
            .sorted()
            .toList());
    return order;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    var actor = actors.manager();
    UUID customer = Input.id(body.get("customer_id")), device = Input.id(body.get("device_id"));
    if (!Boolean.TRUE.equals(db.get("customer", customer).get("active")))
      throw ApiException.conflict("Клиент архивирован");
    if (!customer.equals(db.get("device", device).get("customer_id")))
      throw ApiException.conflict("Устройство принадлежит другому клиенту");
    UUID technician =
        body.get("technician_id") == null ? null : Input.id(body.get("technician_id"));
    if (technician != null) checkTechnician(technician);
    UUID id =
        db.insert(
            "repair_order",
            fields(
                "customer_id",
                customer,
                "device_id",
                device,
                "technician_id",
                technician,
                "accepted_by",
                actor.id(),
                "problem",
                Input.text(body, "problem", 4000, true),
                "intake_condition",
                Input.text(body, "intake_condition", 2000, false),
                "accessories",
                Input.text(body, "accessories", 2000, false),
                "priority",
                Input.choice(
                    body.getOrDefault("priority", "NORMAL"), "LOW", "NORMAL", "HIGH", "URGENT"),
                "status",
                "RECEIVED",
                "estimated_total",
                Input.money(body.getOrDefault("estimated_total", 0)),
                "due_at",
                Input.instant(body.get("due_at")),
                "created_at",
                time.now(),
                "updated_at",
                time.now()));
    history(id, null, "RECEIVED", "Приём устройства");
    audit.record("ORDER_CREATED", "repair_order", id, "Создан заказ");
    if (technician != null)
      audit.record("TECHNICIAN_ASSIGNED", "repair_order", id, "Мастер назначен при приёме");
    for (var recipient :
        db.rows("select id from app_user where active and role in ('OWNER','MANAGER')"))
      audit.notify(Input.id(recipient.get("id")), "ORDER_CREATED", id, "Принят новый заказ");
    audit.notify(technician, "ORDER_ASSIGNED", id, "Назначен новый заказ");
    return details(id);
  }

  @Transactional
  public Map<String, Object> edit(UUID id, Map<String, Object> body) {
    actors.manager();
    var order = access(id, true);
    editable(order);
    version(order, body);
    db.change(
        "repair_order",
        id,
        fields(
            "problem",
            Input.text(body, "problem", 4000, true),
            "priority",
            Input.choice(body.get("priority"), "LOW", "NORMAL", "HIGH", "URGENT"),
            "due_at",
            Input.instant(body.get("due_at")),
            "estimated_total",
            Input.money(body.get("estimated_total")),
            "comment",
            Input.text(body, "comment", 4000, false),
            "updated_at",
            time.now(),
            "version",
            ((Number) order.get("version")).longValue() + 1));
    audit.record("ORDER_UPDATED", "repair_order", id, "Изменены данные заказа");
    return details(id);
  }

  @Transactional
  public Map<String, Object> assign(UUID id, Map<String, Object> body) {
    actors.manager();
    var order = access(id, true);
    editable(order);
    version(order, body);
    UUID technician = Input.id(body.get("technician_id"));
    checkTechnician(technician);
    db.execute(
        "update repair_order set technician_id=?,version=version+1,updated_at=? where id=?",
        technician,
        time.now(),
        id);
    audit.record("TECHNICIAN_ASSIGNED", "repair_order", id, "Назначен мастер");
    audit.notify(technician, "ORDER_ASSIGNED", id, "Вам назначен заказ");
    return details(id);
  }

  @Transactional
  public Map<String, Object> status(UUID id, Map<String, Object> body) {
    var order = access(id, true);
    version(order, body);
    String next = Input.text(body, "status", 30, true);
    if (Set.of("COMPLETED", "DELIVERED", "CANCELLED").contains(next))
      throw ApiException.conflict("Используйте операцию завершения, выдачи или отмены");
    if (next.equals("CANCELLED")
        && !db.rows("select id from repair_work where order_id=?", id).isEmpty())
      throw ApiException.conflict("Сначала урегулируйте выполненные работы");
    if (next.equals("IN_REPAIR")) {
      if (order.get("technician_id") == null) throw ApiException.conflict("Не назначен мастер");
      if (db.rows(
              "select id from repair_approval where order_id=? and amount>=?",
              id,
              order.get("estimated_total"))
          .isEmpty()) throw ApiException.conflict("Сначала зафиксируйте согласование стоимости");
    }
    move(order, next, Input.text(body, "reason", 2000, false));
    return details(id);
  }

  public void move(Map<String, Object> order, String next, String reason) {
    var actor = actors.current();
    String from = order.get("status").toString();
    RepairPolicy.check(from, next, actor.role());
    UUID id = Input.id(order.get("id"));
    db.execute(
        "update repair_order set status=?,version=version+1,updated_at=? where id=?",
        next,
        time.now(),
        id);
    history(id, from, next, reason);
    audit.record("STATUS_CHANGED", "repair_order", id, from + " → " + next);
    if (order.get("technician_id") != null)
      audit.notify(Input.id(order.get("technician_id")), "STATUS_CHANGED", id, "Статус: " + next);
    if (next.equals("READY_FOR_PICKUP"))
      for (var staff :
          db.rows("select id from app_user where active and role in ('OWNER','MANAGER')"))
        audit.notify(
            Input.id(staff.get("id")), "READY_FOR_PICKUP", id, "Устройство готово к выдаче");
  }

  @Transactional
  public Map<String, Object> diagnostic(UUID id, Map<String, Object> body) {
    var order = access(id, true);
    editable(order);
    UUID record =
        db.insert(
            "diagnostic_record",
            fields(
                "order_id",
                id,
                "author_id",
                actors.current().id(),
                "description",
                Input.text(body, "description", 4000, true),
                "result",
                Input.text(body, "result", 4000, false),
                "recommendation",
                Input.text(body, "recommendation", 4000, false),
                "estimated_cost",
                Input.money(body.getOrDefault("estimated_cost", 0)),
                "comment",
                Input.text(body, "comment", 4000, false),
                "created_at",
                time.now()));
    audit.record("DIAGNOSTIC_ADDED", "repair_order", id, "Добавлена диагностика");
    return db.get("diagnostic_record", record);
  }

  @Transactional
  public Map<String, Object> work(UUID id, Map<String, Object> body) {
    var order = access(id, true);
    editable(order);
    if (order.get("technician_id") == null)
      throw ApiException.conflict("Сначала назначьте мастера");
    checkTechnician(Input.id(order.get("technician_id")));
    var price = Input.money(body.getOrDefault("unit_price", 0));
    if (actors.current().technician() && price.signum() > 0) throw ApiException.forbidden();
    UUID record =
        db.insert(
            "repair_work",
            fields(
                "order_id",
                id,
                "technician_id",
                order.get("technician_id"),
                "name",
                Input.text(body, "name", 200, true),
                "description",
                Input.text(body, "description", 4000, false),
                "quantity",
                Input.quantity(body.getOrDefault("quantity", 1)),
                "unit_price",
                price,
                "price_confirmed",
                !actors.current().technician(),
                "created_at",
                time.now()));
    audit.record("WORK_ADDED", "repair_order", id, "Добавлена выполненная работа");
    return db.get("repair_work", record);
  }

  @Transactional
  public Map<String, Object> priceWork(UUID id, UUID work, Map<String, Object> body) {
    actors.manager();
    editable(access(id, true));
    var row = db.get("repair_work", work);
    if (!id.equals(row.get("order_id"))) throw ApiException.forbidden();
    db.execute(
        "update repair_work set unit_price=?,price_confirmed=true where id=?",
        Input.money(body.get("unit_price")),
        work);
    audit.record("WORK_PRICED", "repair_order", id, "Установлена стоимость работы");
    return db.get("repair_work", work);
  }

  @Transactional
  public Map<String, Object> approve(UUID id, Map<String, Object> body) {
    actors.manager();
    var order = access(id, true);
    editable(order);
    String comment = Input.text(body, "comment", 2000, true);
    UUID approval =
        db.insert(
            "repair_approval",
            fields(
                "order_id",
                id,
                "amount",
                Input.money(body.getOrDefault("amount", order.get("estimated_total"))),
                "actor_id",
                actors.current().id(),
                "comment",
                comment,
                "created_at",
                time.now()));
    audit.record("PRICE_APPROVED", "repair_order", id, "Зафиксировано согласие клиента");
    return db.get("repair_approval", approval);
  }

  private void checkTechnician(UUID id) {
    var tech = db.get("app_user", id);
    if (!Boolean.TRUE.equals(tech.get("active")) || !tech.get("role").equals("TECHNICIAN"))
      throw ApiException.conflict("Выберите активного технического специалиста");
  }

  public void version(Map<String, Object> order, Map<String, Object> body) {
    if (Input.version(body) != ((Number) order.get("version")).longValue())
      throw ApiException.conflict("Заказ уже изменён. Обновите данные");
  }

  private void history(UUID id, String from, String to, String reason) {
    db.insert(
        "repair_status_history",
        fields(
            "order_id",
            id,
            "from_status",
            from,
            "to_status",
            to,
            "actor_id",
            actors.current().id(),
            "reason",
            reason,
            "occurred_at",
            time.now()));
  }
}
