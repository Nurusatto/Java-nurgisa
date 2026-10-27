package com.fixflow.report;

import com.fixflow.common.*;
import com.fixflow.security.Actors;
import java.math.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReportService {
  private final Database db;
  private final Actors actors;
  private final DomainTime time;

  public ReportService(Database db, Actors actors, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.time = time;
  }

  public record Period(Timestamp from, Timestamp to) {}

  public Period period(Map<String, String> filters) {
    ZoneId zone =
        ZoneId.of(db.one("select timezone from shop_settings").get("timezone").toString());
    LocalDate today = time.now().atZone(zone).toLocalDate();
    Instant from = bound(filters.get("from"), today.minusDays(29), zone, false),
        to = bound(filters.get("to"), today, zone, true);
    if (!from.isBefore(to) || Duration.between(from, to).toDays() > 3660)
      throw ApiException.invalid("Укажите диапазон до десяти лет");
    return new Period(Timestamp.from(from), Timestamp.from(to));
  }

  private Instant bound(String value, LocalDate fallback, ZoneId zone, boolean end) {
    if (value == null || value.isBlank())
      return (end ? fallback.plusDays(1) : fallback).atStartOfDay(zone).toInstant();
    if (value.length() == 10) {
      var date = LocalDate.parse(value);
      return (end ? date.plusDays(1) : date).atStartOfDay(zone).toInstant();
    }
    return Instant.parse(value);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> dashboard(Map<String, String> filters) {
    var actor = actors.current();
    var p = period(filters);
    String scope = actor.technician() ? " and technician_id=?" : "";
    Object[] args = actor.technician() ? new Object[] {actor.id()} : new Object[] {};
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "total_orders",
        db.one("select count(*) n from repair_order where true" + scope, args).get("n"));
    result.put(
        "by_status",
        db.rows(
            "select status,count(*) count from repair_order where true"
                + scope
                + " group by status order by status",
            args));
    result.put(
        "overdue",
        db.one(
                "select count(*) n from repair_order where due_at<current_timestamp and status not"
                    + " in ('COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')"
                    + scope,
                args)
            .get("n"));
    var periodArgs = new ArrayList<Object>(List.of(p.from(), p.to()));
    if (actor.technician()) periodArgs.add(actor.id());
    result.put(
        "delivered",
        db.one(
                "select count(*) n from repair_order where delivered_at>=? and delivered_at<?"
                    + scope,
                periodArgs.toArray())
            .get("n"));
    result.put(
        "recent_orders",
        db.rows(
            "select id,order_number,status,due_at,problem from repair_order where true"
                + scope
                + " order by created_at desc limit 10",
            args));
    result.put(
        "overdue_orders",
        db.rows(
            "select id,order_number,status,due_at,problem from repair_order where"
                + " due_at<current_timestamp and status not in"
                + " ('COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')"
                + scope
                + " order by due_at limit 10",
            args));
    result.put(
        "upcoming_orders",
        db.rows(
            "select id,order_number,status,due_at from repair_order where due_at between"
                + " current_timestamp and current_timestamp+interval '2 days' and status not in"
                + " ('COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')"
                + scope
                + " order by due_at limit 10",
            args));
    result.put(
        "low_stock",
        db.one("select count(*) n from spare_part where active and quantity<minimum_quantity")
            .get("n"));
    if (!actor.technician())
      result.put(
          "cash_received",
          scalar(
              "select coalesce(sum(amount),0) n from payment where created_at>=? and created_at<?",
              p));
    if (actor.owner()) {
      result.put(
          "expenses",
          scalar(
              "select coalesce(sum(amount),0) n from expense where created_at>=? and created_at<?",
              p));
      result.put(
          "debt",
          debtRows().stream()
              .map(r -> Input.decimal(r.get("debt")))
              .reduce(BigDecimal.ZERO, BigDecimal::add));
      result.put(
          "recent_actions",
          db.rows(
              "select action,entity_type,description,occurred_at from audit_log order by"
                  + " occurred_at desc limit 10"));
    }
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> repairs(Map<String, String> filters) {
    var actor = actors.current();
    var p = period(filters);
    var args = new ArrayList<Object>(List.of(p.from(), p.to()));
    String scope = "";
    if (actor.technician()) {
      scope = " and r.technician_id=?";
      args.add(actor.id());
    }
    for (String key : List.of("status", "priority", "technician_id"))
      if (filters.containsKey(key) && !filters.get(key).isBlank()) {
        scope += " and r." + key + "=?";
        args.add(key.endsWith("_id") ? Input.id(filters.get(key)) : filters.get(key));
      }
    if (!filters.getOrDefault("q", "").isBlank()) {
      scope +=
          " and concat_ws(' ',r.order_number,(select full_name from customer c where"
              + " c.id=r.customer_id),(select model from device d where d.id=r.device_id)) collate"
              + " pg_unicode_fast ilike ?";
      args.add("%" + filters.get("q") + "%");
    }
    String where = " where r.created_at>=? and r.created_at<?" + scope;
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "rows",
        db.rows(
            "select r.id,r.order_number,c.full_name"
                + " customer_name,d.type,d.model,r.status,r.priority,u.full_name"
                + " technician_name,r.created_at,r.completed_at,r.delivered_at,r.due_at,r.final_total"
                + " from repair_order r join customer c on c.id=r.customer_id join device d on"
                + " d.id=r.device_id left join app_user u on u.id=r.technician_id"
                + where
                + " order by r.created_at desc limit 10000",
            args.toArray()));
    result.put(
        "by_status",
        db.rows(
            "select r.status,count(*) count from repair_order r" + where + " group by r.status",
            args.toArray()));
    result.put(
        "by_technician",
        db.rows(
            "select u.full_name technician,count(*) orders from repair_order r left join app_user u"
                + " on u.id=r.technician_id"
                + where
                + " group by u.full_name",
            args.toArray()));
    result.put(
        "device_types",
        db.rows(
            "select d.type,count(*) orders from repair_order r join device d on d.id=r.device_id"
                + where
                + " group by d.type",
            args.toArray()));
    result.put(
        "completion",
        db.one(
            "select count(*) completed,avg(extract(epoch from (r.completed_at-r.created_at))/3600)"
                + " average_hours from repair_order r where r.completed_at>=? and r.completed_at<?"
                + scope,
            args.toArray()));
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> inventory(Map<String, String> filters) {
    var actor = actors.manager();
    var p = period(filters);
    var result = new LinkedHashMap<String, Object>();
    String columns =
        actor.owner()
            ? "id,sku,name,category,quantity,minimum_quantity,stock_value,purchase_price,sale_price"
            : "id,sku,name,category,quantity,minimum_quantity,sale_price";
    var args = new ArrayList<Object>();
    String where = " where true";
    if (filters.containsKey("category") && !filters.get("category").isBlank()) {
      where += " and category=?";
      args.add(filters.get("category"));
    }
    if (Boolean.parseBoolean(filters.getOrDefault("low_stock", "false")))
      where += " and active and quantity<minimum_quantity";
    if (!filters.getOrDefault("q", "").isBlank()) {
      where += " and concat_ws(' ',sku,name) collate pg_unicode_fast ilike ?";
      args.add("%" + filters.get("q") + "%");
    }
    result.put(
        "rows",
        db.rows(
            "select " + columns + " from spare_part" + where + " order by sku limit 10000",
            args.toArray()));
    result.put(
        "usage",
        db.rows(
            "select p.sku,p.name,sum(case when t.direction='OUT' then t.quantity else -t.quantity"
                + " end) used from inventory_transaction t join spare_part p on p.id=t.part_id"
                + " where t.type in ('REPAIR_USE','REPAIR_RETURN') and t.created_at>=? and"
                + " t.created_at<? group by p.sku,p.name order by used desc",
            p.from(),
            p.to()));
    if (actor.owner())
      result.put(
          "stock_value", db.one("select coalesce(sum(stock_value),0) n from spare_part").get("n"));
    return result;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> financial(Map<String, String> filters) {
    actors.owner();
    var p = period(filters);
    var result = new LinkedHashMap<String, Object>();
    BigDecimal
        cash =
            scalar(
                "select coalesce(sum(amount),0) n from payment where created_at>=? and"
                    + " created_at<?",
                p),
        refunds =
            scalar(
                "select coalesce(sum(amount),0) n from refund where created_at>=? and created_at<?",
                p),
        expenses =
            scalar(
                "select coalesce(sum(amount),0) n from expense where created_at>=? and"
                    + " created_at<?",
                p);
    BigDecimal revenue =
        scalar(
                "select coalesce(sum(final_total),0) n from repair_order where status='DELIVERED'"
                    + " and delivered_at>=? and delivered_at<?",
                p)
            .add(
                scalar(
                    "select coalesce(sum(a.amount),0) n from order_price_adjustment a join"
                        + " repair_order r on r.id=a.order_id where r.status='DELIVERED' and"
                        + " a.created_at>=? and a.created_at<?",
                    p));
    BigDecimal cogs =
        scalar(
            "select coalesce(sum(t.total_cost*case when t.direction='OUT' then 1 else -1 end),0) n"
                + " from inventory_transaction t join repair_order r on r.id=t.order_id where"
                + " t.type in ('REPAIR_USE','REPAIR_RETURN') and r.status='DELIVERED' and"
                + " r.delivered_at>=? and r.delivered_at<?",
            p);
    BigDecimal operating =
        scalar(
            "select coalesce(sum(amount),0) n from expense where category<>'PARTS_PURCHASE' and"
                + " created_at>=? and created_at<?",
            p);
    long delivered =
        ((Number)
                db.one(
                        "select count(*) n from repair_order where status='DELIVERED' and"
                            + " delivered_at>=? and delivered_at<?",
                        p.from(),
                        p.to())
                    .get("n"))
            .longValue();
    result.put("cash_received", cash);
    result.put("refunds", refunds);
    result.put("cash_expenses", expenses);
    result.put("cash_flow", cash.subtract(refunds).subtract(expenses));
    result.put("revenue", revenue);
    result.put("parts_cost", cogs);
    result.put("gross_profit", revenue.subtract(cogs));
    result.put("operating_result", revenue.subtract(cogs).subtract(operating));
    result.put(
        "average_check",
        delivered == 0
            ? BigDecimal.ZERO
            : revenue.divide(BigDecimal.valueOf(delivered), 2, RoundingMode.HALF_UP));
    result.put("delivered_orders", delivered);
    result.put("debt_rows", debtRows());
    result.put(
        "total_debt",
        debtRows().stream()
            .map(r -> Input.decimal(r.get("debt")))
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    result.put(
        "rows",
        db.rows(
            "select r.order_number,p.amount,p.method,p.created_at from payment p join repair_order"
                + " r on r.id=p.order_id where p.created_at>=? and p.created_at<? order by"
                + " p.created_at",
            p.from(),
            p.to()));
    result.put(
        "expenses_by_category",
        db.rows(
            "select category,sum(amount) amount from expense where created_at>=? and created_at<?"
                + " group by category",
            p.from(),
            p.to()));
    result.put(
        "work_revenue",
        db.rows(
            "select w.name,sum(round(w.quantity*w.unit_price,2)) revenue from repair_work w join"
                + " repair_order r on r.id=w.order_id where r.status='DELIVERED' and"
                + " r.delivered_at>=? and r.delivered_at<? group by w.name order by revenue desc",
            p.from(),
            p.to()));
    result.put(
        "paid_orders",
        db.one(
                "select count(*) n from repair_order r where r.final_total>0 and r.completed_at>=?"
                    + " and r.completed_at<? and r.final_total+coalesce((select sum(amount) from"
                    + " order_price_adjustment a where a.order_id=r.id),0)=coalesce((select"
                    + " sum(amount) from payment p where p.order_id=r.id),0)-coalesce((select"
                    + " sum(f.amount) from refund f join payment p on p.id=f.payment_id where"
                    + " p.order_id=r.id),0)",
                p.from(),
                p.to())
            .get("n"));
    result.put(
        "monthly",
        db.rows(
            """
with events as (
  select created_at event_time,amount cash_received,0::numeric refunds,0::numeric cash_expenses,0::numeric revenue,0::numeric parts_cost from payment
  union all select created_at,0,amount,0,0,0 from refund
  union all select created_at,0,0,amount,0,0 from expense
  union all select delivered_at,0,0,0,final_total,0 from repair_order where status='DELIVERED'
  union all select a.created_at,0,0,0,a.amount,0 from order_price_adjustment a join repair_order r on r.id=a.order_id where r.status='DELIVERED'
  union all select r.delivered_at,0,0,0,0,t.total_cost*case when t.direction='OUT' then 1 else -1 end
    from inventory_transaction t join repair_order r on r.id=t.order_id
    where r.status='DELIVERED' and t.type in ('REPAIR_USE','REPAIR_RETURN')
)
select to_char(event_time at time zone ?,'YYYY-MM') as period_month,
  sum(cash_received) cash_received,sum(refunds) refunds,sum(cash_expenses) cash_expenses,
  sum(revenue) revenue,sum(parts_cost) parts_cost,sum(revenue)-sum(parts_cost) gross_profit,
  sum(cash_received)-sum(refunds)-sum(cash_expenses) cash_flow
from events where event_time>=? and event_time<? group by 1 order by 1
""",
            db.one("select timezone from shop_settings").get("timezone"),
            p.from(),
            p.to()));
    return result;
  }

  private BigDecimal scalar(String sql, Period p) {
    return Input.decimal(db.one(sql, p.from(), p.to()).get("n"));
  }

  private List<Map<String, Object>> debtRows() {
    return db.rows(
        "select * from (select"
            + " r.id,r.order_number,r.status,coalesce(r.final_total,0)+coalesce((select sum(amount)"
            + " from order_price_adjustment a where a.order_id=r.id),0)-coalesce((select"
            + " sum(amount) from payment p where p.order_id=r.id),0)+coalesce((select sum(f.amount)"
            + " from refund f join payment p on p.id=f.payment_id where p.order_id=r.id),0) debt"
            + " from repair_order r where r.final_total is not null) x where debt>0 order by"
            + " order_number");
  }
}
