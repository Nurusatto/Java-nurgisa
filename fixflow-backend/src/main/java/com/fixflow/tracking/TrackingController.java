package com.fixflow.tracking;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.repair.RepairService;
import com.fixflow.security.Actors;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TrackingController {
  private final Database db;
  private final Actors actors;
  private final RepairService repairs;
  private final Audit audit;
  private final DomainTime time;
  private final SecureRandom random = new SecureRandom();

  public TrackingController(
      Database db, Actors actors, RepairService repairs, Audit audit, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.repairs = repairs;
    this.audit = audit;
    this.time = time;
  }

  @PostMapping("/repair-orders/{id}/tracking")
  @Transactional
  public Map<String, String> issue(@PathVariable UUID id) {
    actors.manager();
    repairs.access(id, true);
    db.execute(
        "update public_tracking_token set revoked_at=? where order_id=? and revoked_at is null",
        time.now(),
        id);
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.insert(
        "public_tracking_token",
        fields("order_id", id, "token_hash", Idempotency.sha256(token), "created_at", time.now()));
    audit.record("TRACKING_ISSUED", "repair_order", id, "Выдана публичная ссылка");
    return Map.of("token", token, "path", "/api/v1/public/repair-status/" + token);
  }

  @DeleteMapping("/repair-orders/{id}/tracking")
  @Transactional
  public void revoke(@PathVariable UUID id) {
    actors.manager();
    repairs.access(id, true);
    db.execute(
        "update public_tracking_token set revoked_at=? where order_id=? and revoked_at is null",
        time.now(),
        id);
    audit.record("TRACKING_REVOKED", "repair_order", id, "Публичные ссылки отозваны");
  }

  @GetMapping("/public/repair-status/{token}")
  public Map<String, Object> status(@PathVariable String token) {
    if (!token.matches("[A-Za-z0-9_-]{43}"))
      throw new ApiException(404, "NOT_FOUND", "Ссылка недействительна");
    return db.one(
        "select r.order_number,d.type"
            + " device_type,d.manufacturer,d.model,r.status,r.updated_at,r.due_at"
            + " expected_completion from public_tracking_token t join repair_order r on"
            + " r.id=t.order_id join device d on d.id=r.device_id where t.token_hash=? and"
            + " t.revoked_at is null",
        Idempotency.sha256(token));
  }
}
