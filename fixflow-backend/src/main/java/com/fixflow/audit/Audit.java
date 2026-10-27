package com.fixflow.audit;

import static com.fixflow.common.Database.fields;

import com.fixflow.common.*;
import com.fixflow.security.Actors;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class Audit {
  private final Database db;
  private final Actors actors;
  private final DomainTime time;

  public Audit(Database db, Actors actors, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.time = time;
  }

  public void record(String action, String type, UUID id, String description) {
    db.insert(
        "audit_log",
        fields(
            "actor_id",
            actors.current().id(),
            "action",
            action,
            "entity_type",
            type,
            "entity_id",
            id,
            "description",
            description,
            "occurred_at",
            time.now()));
  }

  public void notify(UUID recipient, String type, UUID order, String message) {
    if (recipient != null)
      db.insert(
          "notification",
          fields(
              "recipient_id",
              recipient,
              "type",
              type,
              "order_id",
              order,
              "message",
              message,
              "created_at",
              time.now()));
  }

  public void failedLogin() {
    db.insert(
        "audit_log",
        fields(
            "action",
            "LOGIN_FAILED",
            "entity_type",
            "user",
            "description",
            "Неудачная попытка входа",
            "occurred_at",
            time.now()));
  }
}
