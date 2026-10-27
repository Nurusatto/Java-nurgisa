package com.fixflow.notification;

import static com.fixflow.common.Database.fields;

import com.fixflow.common.*;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
  private final Database db;
  private final Actors actors;
  private final DomainTime time;

  public NotificationService(Database db, Actors actors, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.time = time;
  }

  @Transactional
  public Map<String, Object> create(Map<String, Object> body) {
    actors.manager();
    UUID recipient = Input.id(body.get("recipient_id"));
    if (!Boolean.TRUE.equals(db.get("app_user", recipient).get("active")))
      throw ApiException.conflict("Получатель отключён");
    UUID order = body.get("order_id") == null ? null : Input.id(body.get("order_id"));
    UUID id =
        db.insert(
            "notification",
            fields(
                "recipient_id",
                recipient,
                "type",
                "CUSTOM",
                "order_id",
                order,
                "message",
                Input.text(body, "message", 2000, true),
                "created_at",
                time.now()));
    return db.get("notification", id);
  }

  @Transactional
  public void read(UUID id) {
    int changed =
        db.execute(
            "update notification set read_at=coalesce(read_at,?) where id=? and recipient_id=?",
            time.now(),
            id,
            actors.current().id());
    if (changed == 0) throw new ApiException(404, "NOT_FOUND", "Уведомление не найдено");
  }

  public Map<String, Object> unread() {
    return db.one(
        "select count(*) unread from notification where recipient_id=? and read_at is null",
        actors.current().id());
  }

  @Scheduled(initialDelay = 30000, fixedDelay = 60000)
  @Transactional
  public void deadlines() {
    for (var order :
        db.rows(
            "select id,order_number,technician_id,due_at from repair_order where due_at<? and"
                + " status not in ('COMPLETED','READY_FOR_PICKUP','DELIVERED','CANCELLED')",
            java.sql.Timestamp.from(time.now().plusSeconds(86400)))) {
      var due = ((java.time.Instant) order.get("due_at"));
      String type = due.isBefore(time.now()) ? "ORDER_OVERDUE" : "ORDER_DUE_SOON";
      var recipients =
          db.rows(
              "select id from app_user where active and (role in ('OWNER','MANAGER') or id=?)",
              order.get("technician_id"));
      for (var recipient : recipients) {
        String key = order.get("id") + ":" + recipient.get("id") + ":" + type + ":" + due;
        db.execute(
            "insert into notification(id,recipient_id,type,order_id,message,dedup_key,created_at)"
                + " values (?,?,?,?,?,?,?) on conflict(dedup_key) do nothing",
            UUID.randomUUID(),
            recipient.get("id"),
            type,
            order.get("id"),
            (type.equals("ORDER_OVERDUE") ? "Просрочен заказ " : "Приближается срок заказа ")
                + order.get("order_number"),
            key,
            time.now());
      }
    }
  }
}
