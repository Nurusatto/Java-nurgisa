package com.fixflow.settings;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.security.Actors;
import java.time.ZoneId;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shop-settings")
public class SettingsController {
  private final Database db;
  private final Actors actors;
  private final Audit audit;

  public SettingsController(Database db, Actors actors, Audit audit) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
  }

  @GetMapping
  public Map<String, Object> get() {
    actors.current();
    return db.one("select * from shop_settings");
  }

  @PutMapping
  @Transactional
  public Map<String, Object> save(@RequestBody Map<String, Object> body) {
    actors.owner();
    var previous = db.one("select * from shop_settings for update");
    if (Input.version(body) != ((Number) previous.get("version")).longValue())
      throw ApiException.conflict("Настройки уже изменены");
    String zone = Input.text(body, "timezone", 100, true);
    ZoneId.of(zone);
    String currency = Input.text(body, "currency", 3, true).toUpperCase(Locale.ROOT);
    java.util.Currency.getInstance(currency);
    if (!currency.equals(previous.get("currency"))
        && (!db.rows("select id from repair_order limit 1").isEmpty()
            || !db.rows("select id from expense limit 1").isEmpty()
            || !db.rows("select id from inventory_transaction limit 1").isEmpty()
            || !db.rows("select id from purchase_order limit 1").isEmpty()))
      throw ApiException.conflict("Валюту нельзя менять после начала учёта");
    if (!zone.equals(previous.get("timezone"))
        && !db.rows("select id from warranty limit 1").isEmpty())
      throw ApiException.conflict("Часовой пояс нельзя менять после выдачи гарантий");
    int days = Integer.parseInt(body.get("warranty_days").toString());
    if (days < 0 || days > 3650) throw ApiException.invalid("Срок гарантии: от 0 до 3650 дней");
    UUID id = Input.id(previous.get("id"));
    db.change(
        "shop_settings",
        id,
        fields(
            "name",
            Input.text(body, "name", 150, true),
            "phone",
            Input.text(body, "phone", 32, false),
            "address",
            Input.text(body, "address", 2000, false),
            "timezone",
            zone,
            "currency",
            currency,
            "warranty_days",
            days,
            "version",
            Input.version(body) + 1));
    audit.record("SETTINGS_UPDATED", "shop_settings", id, "Изменены настройки мастерской");
    return get();
  }
}
