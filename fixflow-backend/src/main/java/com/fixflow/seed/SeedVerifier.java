package com.fixflow.seed;

import com.fixflow.common.Database;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SeedVerifier {
  private final Database db;

  public SeedVerifier(Database db) {
    this.db = db;
  }

  public void verify() {
    Map<String, Integer> minimum =
        Map.ofEntries(
            Map.entry("app_user", 8),
            Map.entry("customer", 30),
            Map.entry("device", 40),
            Map.entry("repair_order", 80),
            Map.entry("diagnostic_record", 50),
            Map.entry("repair_work", 60),
            Map.entry("spare_part", 40),
            Map.entry("supplier", 5),
            Map.entry("purchase_order", 10),
            Map.entry("payment", 60),
            Map.entry("expense", 25),
            Map.entry("warranty", 15));
    minimum.forEach(
        (table, count) -> {
          if (number("select count(*) n from " + table) < count)
            throw new IllegalStateException("Incomplete demonstration data: " + table);
        });
    requireZero(
        "select count(*) n from spare_part p where p.quantity<>coalesce((select sum(case when"
            + " direction='IN' then quantity else -quantity end) from inventory_transaction t where"
            + " t.part_id=p.id),0)",
        "Stock ledger mismatch");
    requireZero(
        "select count(*) n from spare_part p where p.stock_value<>coalesce((select sum(case when"
            + " direction='IN' then total_cost else -total_cost end) from inventory_transaction t"
            + " where t.part_id=p.id),0)",
        "Stock valuation mismatch");
    requireZero(
        "select count(*) n from (select part_id,after_quantity,sum(case when direction='IN'"
            + " then quantity else -quantity end) over(partition by part_id order by created_at,id)"
            + " expected from inventory_transaction) t where after_quantity<>expected",
        "Stock history is not chronological");
    requireZero(
        "select count(*) n from audit_log a join app_user u on u.id=a.actor_id where"
            + " a.occurred_at<u.created_at",
        "Audit event predates its actor");
    requireZero(
        "select count(*) n from (select device_id from repair_order where status not in"
            + " ('DELIVERED','CANCELLED') group by device_id having count(*)>1) d",
        "A device has multiple active demonstration repairs");
    requireZero(
        "select count(*) n from payment p where p.amount<(select coalesce(sum(amount),0) from"
            + " refund f where f.payment_id=p.id)",
        "Refund exceeds payment");
    requireZero(
        "select count(*) n from purchase_order_item i where i.quantity<(select"
            + " coalesce(sum(quantity),0) from purchase_receipt_item r where"
            + " r.purchase_order_item_id=i.id)",
        "Over-received purchase");
    requireZero(
        "select count(*) n from warranty w join repair_order r on r.id=w.order_id where"
            + " r.status<>'DELIVERED' or w.starts_on<>(r.delivered_at at time zone (select timezone"
            + " from shop_settings))::date",
        "Invalid warranty");
    requireZero(
        "select count(*) n from repair_order r where r.status='DELIVERED' and"
            + " r.final_total+coalesce((select sum(amount) from order_price_adjustment a where"
            + " a.order_id=r.id),0)<>coalesce((select sum(amount) from payment p where"
            + " p.order_id=r.id),0)-coalesce((select sum(f.amount) from refund f join payment p on"
            + " p.id=f.payment_id where p.order_id=r.id),0)",
        "Delivered order is not settled");
    if (number("select count(distinct status) n from repair_order") < 10)
      throw new IllegalStateException("Missing order statuses");
  }

  private long number(String sql) {
    return ((Number) db.one(sql).get("n")).longValue();
  }

  private void requireZero(String sql, String description) {
    if (number(sql) != 0) throw new IllegalStateException(description);
  }
}
