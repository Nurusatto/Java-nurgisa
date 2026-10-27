package com.fixflow.catalog;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import com.fixflow.contracts.PageResponse;
import com.fixflow.security.Actors;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final PasswordEncoder passwords;
  private final DomainTime time;
  private final RequestValidation validation;

  public CatalogService(
      Database db,
      Actors actors,
      Audit audit,
      PasswordEncoder passwords,
      DomainTime time,
      RequestValidation validation) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.passwords = passwords;
    this.time = time;
    this.validation = validation;
  }

  private String table(String resource) {
    return switch (resource) {
      case "customers" -> "customer";
      case "devices" -> "device";
      case "users", "technicians" -> "app_user";
      case "parts" -> "spare_part";
      case "suppliers" -> "supplier";
      default -> throw new ApiException(404, "NOT_FOUND", "Раздел не найден");
    };
  }

  private void read(String resource) {
    if (resource.equals("users")) actors.owner();
    else if (resource.equals("technicians") || resource.equals("parts")) actors.current();
    else actors.manager();
  }

  public PageResponse<Map<String, Object>> list(String resource, Map<String, String> filters) {
    read(resource);
    String table = table(resource);
    int page = Integer.parseInt(filters.getOrDefault("page", "0"));
    int size = Integer.parseInt(filters.getOrDefault("size", "20"));
    if (page < 0 || size < 1 || size > 100) throw ApiException.invalid("Недопустимая пагинация");
    String columns =
        resource.equals("users") || resource.equals("technicians")
            ? "id,email,full_name,role,active,version,created_at,updated_at"
            : "*";
    if (resource.equals("parts") && !actors.current().owner())
      columns =
          "id,name,sku,category,manufacturer,sale_price,quantity,minimum_quantity,storage_location,active,version,created_at,updated_at";
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>();
    if (resource.equals("technicians")) clauses.add("role='TECHNICIAN' and active=true");
    String search = filters.getOrDefault("q", "").trim();
    if (search.length() > 200) throw ApiException.invalid("Поиск слишком длинный");
    if (!search.isBlank()) {
      String expression =
          switch (resource) {
            case "devices" -> "concat_ws(' ',manufacturer,model,serial_number,imei)";
            case "parts" -> "concat_ws(' ',name,sku,category)";
            case "suppliers" -> "concat_ws(' ',name,email,phone)";
            default ->
                "concat_ws(' ',full_name,email"
                    + (resource.equals("customers") ? ",phone" : "")
                    + ")";
          };
      clauses.add(expression + " collate pg_unicode_fast ilike ?");
      args.add("%" + search + "%");
    }
    if (resource.equals("devices") && filters.containsKey("customer_id")) {
      clauses.add("customer_id=?");
      args.add(Input.id(filters.get("customer_id")));
    }
    if (filters.containsKey("active") && !resource.equals("devices")) {
      clauses.add("active=?");
      args.add(Boolean.parseBoolean(filters.get("active")));
    }
    if (resource.equals("parts") && filters.containsKey("category")) {
      clauses.add("category=?");
      args.add(filters.get("category"));
    }
    if (resource.equals("parts")
        && Boolean.parseBoolean(filters.getOrDefault("low_stock", "false")))
      clauses.add("quantity<minimum_quantity");
    String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    String sort = filters.getOrDefault("sort", "created_at");
    Set<String> allowed =
        switch (resource) {
          case "devices" -> Set.of("created_at", "manufacturer", "model");
          case "parts" -> Set.of("created_at", "name", "sku", "category", "quantity");
          case "suppliers" -> Set.of("created_at", "name", "email");
          default -> Set.of("created_at", "full_name", "email");
        };
    if (!allowed.contains(sort)) throw ApiException.invalid("Недопустимая сортировка");
    String direction = Input.choice(filters.getOrDefault("direction", "desc"), "asc", "desc");
    long count =
        ((Number) db.one("select count(*) as n from " + table + where, args.toArray()).get("n"))
            .longValue();
    args.add(size);
    args.add((long) page * size);
    return new PageResponse<>(
        db.rows(
            "select "
                + columns
                + " from "
                + table
                + where
                + " order by "
                + sort
                + " "
                + direction
                + ",id limit ? offset ?",
            args.toArray()),
        count,
        page,
        size);
  }

  public Map<String, Object> get(String resource, UUID id) {
    read(resource);
    var row = db.get(table(resource), id);
    row.remove("password_hash");
    if (resource.equals("parts") && !actors.current().owner()) {
      row.remove("purchase_price");
      row.remove("stock_value");
    }
    if (resource.equals("customers")) {
      row.put(
          "potential_duplicates",
          db.rows(
              "select id,full_name,phone,email from customer where id<>? and"
                  + " (regexp_replace(phone,'[^0-9]','','g')=regexp_replace(?,'[^0-9]','','g') or"
                  + " (email is not null and email<>'' and lower(email)=lower(?)))",
              id,
              row.get("phone"),
              row.get("email")));
      row.put(
          "devices",
          db.rows(
              "select id,type,manufacturer,model,serial_number from device where customer_id=?"
                  + " order by created_at desc",
              id));
      row.put(
          "open_orders",
          db.one(
                  "select count(*) n from repair_order where customer_id=? and status not in"
                      + " ('DELIVERED','CANCELLED')",
                  id)
              .get("n"));
      row.put(
          "total_paid",
          db.one(
                  "select coalesce((select sum(p.amount) from payment p join repair_order r on"
                      + " r.id=p.order_id where r.customer_id=?),0)-coalesce((select sum(f.amount)"
                      + " from refund f join payment p on p.id=f.payment_id join repair_order r on"
                      + " r.id=p.order_id where r.customer_id=?),0) n",
                  id,
                  id)
              .get("n"));
    }
    if (resource.equals("devices"))
      row.put(
          "warranties",
          db.rows(
              "select w.* from warranty w join repair_order r on r.id=w.order_id where"
                  + " r.device_id=? order by w.created_at desc",
              id));
    if (resource.equals("suppliers") && actors.current().owner())
      row.put(
          "purchases",
          db.rows(
              "select id,number,status,expected_at,created_at from purchase_order where"
                  + " supplier_id=? order by created_at desc",
              id));
    return row;
  }

  @Transactional
  public Map<String, Object> save(String resource, UUID id, Map<String, Object> body) {
    if (Set.of("users", "parts", "suppliers").contains(resource)) actors.owner();
    else actors.manager();
    if (resource.equals("technicians")) throw ApiException.forbidden();
    if (resource.equals("customers"))
      validation.check(body, com.fixflow.contracts.CustomerInput.class);
    String table = table(resource);
    Map<String, Object> old = id == null ? null : db.lock(table, id);
    if (old != null && Input.version(body) != ((Number) old.get("version")).longValue())
      throw ApiException.conflict("Запись уже изменена. Обновите данные");
    var values = new LinkedHashMap<String, Object>();
    switch (resource) {
      case "parts" -> {
        if (body.containsKey("quantity") || body.containsKey("stock_value"))
          throw ApiException.invalid("Остаток меняется только складскими операциями");
        values.put("name", Input.text(body, "name", 200, true));
        values.put("sku", Input.text(body, "sku", 80, true).toUpperCase(Locale.ROOT));
        values.put(
            "category",
            Input.choice(
                body.get("category"),
                "STORAGE",
                "MEMORY",
                "BATTERY",
                "DISPLAY",
                "KEYBOARD",
                "MOTHERBOARD",
                "CABLE",
                "COOLING",
                "CONNECTOR",
                "TOOLS",
                "OTHER"));
        values.put("manufacturer", Input.text(body, "manufacturer", 150, false));
        values.put(
            "supplier_id",
            body.get("supplier_id") == null ? null : Input.id(body.get("supplier_id")));
        values.put("purchase_price", Input.money(body.get("purchase_price")));
        values.put("sale_price", Input.money(body.get("sale_price")));
        var min = Input.decimal(body.getOrDefault("minimum_quantity", 0));
        if (min.signum() < 0 || min.scale() > 3 || min.precision() > 12)
          throw ApiException.invalid("Некорректный минимальный остаток");
        values.put("minimum_quantity", min);
        values.put("storage_location", Input.text(body, "storage_location", 150, false));
        values.put("active", active(body));
      }
      case "suppliers" -> {
        values.put("name", Input.text(body, "name", 200, true));
        values.put("contact_name", Input.text(body, "contact_name", 150, false));
        values.put("phone", Input.text(body, "phone", 32, false));
        values.put("email", email(body, false));
        values.put("address", Input.text(body, "address", 2000, false));
        values.put("comment", Input.text(body, "comment", 4000, false));
        values.put("active", active(body));
      }
      case "customers" -> {
        values.put("full_name", Input.text(body, "full_name", 150, true));
        String phone = Input.text(body, "phone", 32, true);
        if (!phone.matches("[+0-9 ()-]{7,32}")) throw ApiException.invalid("Некорректный телефон");
        values.put("phone", phone);
        values.put("email", email(body, false));
        values.put("comment", Input.text(body, "comment", 4000, false));
        values.put("active", active(body));
      }
      case "devices" -> {
        UUID customer = Input.id(body.get("customer_id"));
        if (!Boolean.TRUE.equals(db.get("customer", customer).get("active")))
          throw ApiException.conflict("Клиент архивирован");
        if (old != null
            && !Input.id(old.get("customer_id")).equals(customer)
            && !db.rows("select id from repair_order where device_id=? limit 1", id).isEmpty())
          throw ApiException.conflict("Устройство с историей нельзя передать другому клиенту");
        values.put("customer_id", customer);
        values.put(
            "type",
            Input.choice(
                body.get("type"),
                "LAPTOP",
                "DESKTOP",
                "SMARTPHONE",
                "TABLET",
                "MONITOR",
                "PRINTER",
                "CONSOLE",
                "OTHER"));
        for (String key : List.of("manufacturer", "model"))
          values.put(key, Input.text(body, key, 150, true));
        values.put("serial_number", Input.text(body, "serial_number", 150, false));
        String imei = Input.text(body, "imei", 15, false);
        if (!imei.isEmpty() && !imei.matches("[0-9]{15}"))
          throw ApiException.invalid("IMEI должен содержать 15 цифр");
        values.put("imei", imei);
        values.put("comment", Input.text(body, "comment", 4000, false));
      }
      case "users" -> {
        String role = Input.choice(body.get("role"), "OWNER", "MANAGER", "TECHNICIAN");
        boolean active = active(body);
        if (old != null) {
          boolean roleChanged = !role.equals(old.get("role"));
          if (id.equals(actors.current().id()) && (roleChanged || !active))
            throw ApiException.conflict("Нельзя менять собственную роль или отключать себя");
          if ((roleChanged || !active) && old.get("role").equals("OWNER")) {
            db.rows("select id from app_user where role='OWNER' for update");
            if (((Number)
                        db.one(
                                "select count(*) n from app_user where role='OWNER' and active=true"
                                    + " and id<>?",
                                id)
                            .get("n"))
                    .intValue()
                == 0) throw ApiException.conflict("Нельзя отключить последнего владельца");
          }
          db.execute("delete from spring_session where principal_name=?", old.get("email"));
          if (roleChanged)
            audit.record("USER_ROLE_CHANGED", "app_user", id, old.get("role") + " → " + role);
          if (Boolean.TRUE.equals(old.get("active")) && !active)
            audit.record("USER_DEACTIVATED", "app_user", id, "Учётная запись отключена");
        }
        values.put("email", email(body, true));
        values.put("full_name", Input.text(body, "full_name", 150, true));
        values.put("role", role);
        values.put("active", active);
        String password = Input.secret(body, "password", 100, old == null);
        if (!password.isBlank()) {
          if (password.length() < 10)
            throw ApiException.invalid("Пароль должен содержать минимум 10 символов");
          values.put("password_hash", passwords.encode(password));
        }
      }
      default -> throw ApiException.forbidden();
    }
    values.put("updated_at", time.now());
    if (id == null) {
      values.put("created_at", time.now());
      id = db.insert(table, values);
    } else {
      values.put("version", ((Number) old.get("version")).longValue() + 1);
      db.change(table, id, values);
    }
    audit.record(old == null ? "CREATED" : "UPDATED", table, id, "Изменена запись справочника");
    return get(resource, id);
  }

  private boolean active(Map<String, Object> body) {
    Object v = body.getOrDefault("active", true);
    if (!Set.of("true", "false").contains(v.toString()))
      throw ApiException.invalid("Некорректный статус активности");
    return Boolean.parseBoolean(v.toString());
  }

  private String email(Map<String, Object> body, boolean required) {
    String email = Input.text(body, "email", 254, required).toLowerCase(Locale.ROOT);
    if (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw ApiException.invalid("Некорректный email");
    return email;
  }
}
