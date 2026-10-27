package com.fixflow.seed;

import static com.fixflow.common.Database.fields;

import com.fixflow.catalog.CatalogService;
import com.fixflow.common.*;
import com.fixflow.finance.FinanceService;
import com.fixflow.inventory.InventoryService;
import com.fixflow.procurement.ProcurementService;
import com.fixflow.repair.RepairService;
import com.fixflow.security.Actors;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "fixflow.demo-enabled", havingValue = "true")
@Order(2)
public class DemoData implements CommandLineRunner {
  private final Database db;
  private final Actors actors;
  private final DomainTime time;
  private final CatalogService catalog;
  private final RepairService repairs;
  private final InventoryService inventory;
  private final ProcurementService purchases;
  private final FinanceService finance;
  private final SeedVerifier verifier;

  public DemoData(
      Database db,
      Actors actors,
      DomainTime time,
      CatalogService catalog,
      RepairService repairs,
      InventoryService inventory,
      ProcurementService purchases,
      FinanceService finance,
      SeedVerifier verifier) {
    this.db = db;
    this.actors = actors;
    this.time = time;
    this.catalog = catalog;
    this.repairs = repairs;
    this.inventory = inventory;
    this.purchases = purchases;
    this.finance = finance;
    this.verifier = verifier;
  }

  @Override
  @Transactional
  public void run(String... args) {
    db.rows("select pg_advisory_xact_lock(7349821)");
    if (!db.rows("select dataset_version from seed_run where dataset_version='workshop-v1'")
        .isEmpty()) {
      return;
    }
    Instant base = Instant.now();
    UUID owner = user("owner");
    var managers = List.of(user("manager1"), user("manager2"));
    var technicians = new ArrayList<UUID>();
    for (int i = 1; i <= 5; i++) technicians.add(user("tech" + i));
    actors.as(
        owner,
        () -> {
          var suppliers = new ArrayList<UUID>();
          var parts = new ArrayList<UUID>();
          var customers = new ArrayList<UUID>();
          var devices = new ArrayList<UUID>();
          var purchaseIds = new ArrayList<UUID>();
          time.at(
              base.minus(Duration.ofDays(400)),
              () -> {
                String[] companies = {
                  "Орбита Компонент",
                  "Мастер Деталь",
                  "Техно Запас",
                  "Сервис Линия",
                  "Контур Снабжение"
                };
                for (int i = 0; i < 5; i++)
                  suppliers.add(
                      id(
                          catalog.save(
                              "suppliers",
                              null,
                              fields(
                                  "name",
                                  companies[i],
                                  "contact_name",
                                  "Контакт " + (i + 1),
                                  "email",
                                  "supplier" + i + "@example.test",
                                  "phone",
                                  String.format("+7700002%04d", i),
                                  "address",
                                  "Демонстрационный адрес " + (i + 1)))));
                String[] partNames = {
                  "SSD 512 GB",
                  "Память DDR4 8 GB",
                  "Аккумулятор",
                  "Дисплей",
                  "Клавиатура",
                  "Плата питания",
                  "Кабель USB-C",
                  "Вентилятор",
                  "Разъём питания",
                  "Термопаста, упаковка",
                  "Набор креплений"
                };
                String[] categories = {
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
                  "OTHER"
                };
                for (int i = 0; i < 40; i++) {
                  UUID part =
                      id(
                          catalog.save(
                              "parts",
                              null,
                              fields(
                                  "name",
                                  partNames[i % 11] + " · вариант " + (i / 11 + 1),
                                  "sku",
                                  String.format("DEMO-%04d", i + 1),
                                  "category",
                                  categories[i % 11],
                                  "manufacturer",
                                  "Demo Components",
                                  "supplier_id",
                                  suppliers.get(i % 5),
                                  "purchase_price",
                                  1000 + i * 100,
                                  "sale_price",
                                  2000 + i * 150,
                                  "minimum_quantity",
                                  3,
                                  "storage_location",
                                  "Стеллаж " + (i / 10 + 1))));
                  parts.add(part);
                  if (i % 10 != 0)
                    inventory.receipt(
                        part,
                        fields(
                            "quantity",
                            i % 10 == 1 ? 1 : 35,
                            "unit_cost",
                            1000 + i * 100,
                            "reason",
                            "Начальный демонстрационный остаток"));
                }
                String[] first = {
                  "Алия", "Данияр", "Мария", "Ерлан", "Анна", "Тимур", "Елена", "Арман", "София",
                  "Руслан"
                };
                String[] last = {"Серикова", "Иванов", "Нуртаева"};
                for (int i = 0; i < 30; i++)
                  customers.add(
                      id(
                          catalog.save(
                              "customers",
                              null,
                              fields(
                                  "full_name",
                                  first[i % 10] + " " + last[i / 10],
                                  "phone",
                                  String.format("+7700001%04d", i),
                                  "email",
                                  "customer" + (i + 1) + "@example.test",
                                  "comment",
                                  "Вымышленная запись для демонстрации"))));
                String[] types = {
                  "LAPTOP",
                  "DESKTOP",
                  "SMARTPHONE",
                  "TABLET",
                  "MONITOR",
                  "PRINTER",
                  "CONSOLE",
                  "OTHER"
                };
                String[] models = {
                  "ThinkPad T14",
                  "OptiPlex 5090",
                  "Galaxy A52",
                  "iPad Air",
                  "UltraSharp U24",
                  "LaserJet M1132",
                  "PlayStation 5",
                  "Audio Station"
                };
                String[] manufacturers = {
                  "Lenovo", "Dell", "Samsung", "Apple", "Dell", "HP", "Sony", "Demo"
                };
                for (int i = 0; i < 60; i++)
                  devices.add(
                      id(
                          catalog.save(
                              "devices",
                              null,
                              fields(
                                  "customer_id",
                                  customers.get(i % 30),
                                  "type",
                                  types[i % 8],
                                  "manufacturer",
                                  manufacturers[i % 8],
                                  "model",
                                  models[i % 8],
                                  "serial_number",
                                  String.format("FIX-DEMO-%05d", i + 1),
                                  "imei",
                                  i % 8 == 2 ? String.format("%015d", 1000 + i) : "",
                                  "comment",
                                  "Вымышленный идентификатор"))));
                return null;
              });
          for (int i = 0; i < 10; i++) {
            final int n = i;
            time.at(
                base.minus(Duration.ofDays(i % 5 == 0 || i % 5 == 1 ? 10 : 240))
                    .plusSeconds(i * 3600L),
                () -> {
                  var purchase =
                      purchases.create(
                          fields(
                              "supplier_id",
                              suppliers.get(n % 5),
                              "expected_at",
                              time.now().plus(Duration.ofDays(7)),
                              "comment",
                              "Плановая закупка запчастей"));
                  UUID purchaseId = id(purchase);
                  purchaseIds.add(purchaseId);
                  for (int j = 0; j < 2; j++) {
                    int partIndex = (n * 3 + j) % 40;
                    purchases.item(
                        purchaseId,
                        null,
                        fields(
                            "part_id",
                            parts.get(partIndex),
                            "quantity",
                            5 + j,
                            "unit_price",
                            1000 + partIndex * 100));
                  }
                  int state = n % 5;
                  if (state != 0) purchases.order(purchaseId);
                  if (state == 4) purchases.cancel(purchaseId);
                  if (state == 2 || state == 3) {
                    var items = purchases.items(purchaseId);
                    var lines = new ArrayList<Map<String, Object>>();
                    for (var item : items)
                      lines.add(
                          fields(
                              "item_id",
                              item.get("id"),
                              "quantity",
                              state == 3 ? item.get("quantity") : 2));
                    purchases.receive(purchaseId, Map.of("items", lines));
                  }
                  return null;
                });
          }
          var orderIndices =
              java.util.stream.IntStream.range(0, 80)
                  .boxed()
                  .sorted(
                      Comparator.comparingInt((Integer index) -> age(index))
                          .reversed()
                          .thenComparingInt(Integer::intValue))
                  .toList();
          var daySlots = new HashMap<Integer, Integer>();
          for (int n : orderIndices) {
            String target = target(n);
            int daysOld = age(n);
            int slot = daySlots.merge(daysOld, 1, Integer::sum) - 1;
            Instant created = base.minus(Duration.ofDays(daysOld)).plusSeconds(slot * 7200L);
            var cursor = new Cursor(created);
            actors.as(
                managers.get(n % 2),
                () -> {
                  var device = db.get("device", devices.get(n < 24 ? n : n - 24));
                  var scenario = scenario(n);
                  UUID technician = technicians.get(n % 5);
                  int quote = 15000 + (n % 5) * 1000;
                  UUID order =
                      id(
                          step(
                              cursor,
                              () ->
                                  repairs.create(
                                      fields(
                                          "customer_id",
                                          device.get("customer_id"),
                                          "device_id",
                                          device.get("id"),
                                          "technician_id",
                                          technicians.get(n % 5),
                                          "problem",
                                          scenario.failure(),
                                          "intake_condition",
                                          n % 3 == 0
                                              ? "Следы эксплуатации, царапины на корпусе"
                                              : "Без видимых повреждений",
                                          "accessories",
                                          "Устройство и блок питания",
                                          "priority",
                                          n % 9 == 0 ? "URGENT" : n % 5 == 0 ? "HIGH" : "NORMAL",
                                          "estimated_total",
                                          quote,
                                          "due_at",
                                          created.plus(Duration.ofDays(n % 3 == 0 ? 1 : 5))))));
                  if (target.equals("RECEIVED")) return null;
                  stepAs(cursor, technician, () -> move(order, "DIAGNOSIS"));
                  stepAs(
                      cursor,
                      technician,
                      () ->
                          repairs.diagnostic(
                              order,
                              fields(
                                  "description",
                                  scenario.failure(),
                                  "result",
                                  "Проверены питание, соединения и работоспособность узлов",
                                  "recommendation",
                                  scenario.work(),
                                  "estimated_cost",
                                  quote)));
                  step(
                      cursor,
                      () ->
                          repairs.work(
                              order,
                              fields(
                                  "name",
                                  "Диагностика устройства",
                                  "description",
                                  "Проверка заявленной неисправности и согласование плана ремонта",
                                  "quantity",
                                  1,
                                  "unit_price",
                                  750)));
                  if (target.equals("DIAGNOSIS")) return null;
                  if (target.equals("CANCELLED")) {
                    step(
                        cursor,
                        () ->
                            finance.cancel(
                                order,
                                fields(
                                    "version",
                                    version(order),
                                    "reason",
                                    "Клиент отказался от ремонта; стоимость диагностики не"
                                        + " взимается")));
                    return null;
                  }
                  stepAs(cursor, technician, () -> move(order, "WAITING_FOR_APPROVAL"));
                  if (target.equals("WAITING_FOR_APPROVAL")) return null;
                  step(
                      cursor,
                      () ->
                          repairs.approve(
                              order,
                              fields(
                                  "amount",
                                  quote,
                                  "comment",
                                  "Клиент согласовал стоимость по телефону; запись"
                                      + " администратора")));
                  if (target.equals("WAITING_FOR_PARTS")) {
                    stepAs(
                        cursor,
                        technician,
                        () ->
                            repairs.status(
                                order,
                                fields(
                                    "status",
                                    "WAITING_FOR_PARTS",
                                    "version",
                                    version(order),
                                    "reason",
                                    "Ожидается совместимая деталь для конкретной модели")));
                    return null;
                  }
                  stepAs(cursor, technician, () -> move(order, "IN_REPAIR"));
                  if (scenario.category() != null) {
                    var stock =
                        db.one(
                            "select id from spare_part where category=? and active and quantity>=2"
                                + " order by quantity desc,sku limit 1",
                            scenario.category());
                    stepAs(
                        cursor,
                        technician,
                        () ->
                            inventory.use(
                                order, fields("part_id", stock.get("id"), "quantity", 1)));
                  }
                  step(
                      cursor,
                      () ->
                          repairs.work(
                              order,
                              fields(
                                  "name",
                                  scenario.work(),
                                  "description",
                                  "Работа выполнена по согласованному плану, результат проверен",
                                  "quantity",
                                  1,
                                  "unit_price",
                                  2500 + n % 5 * 500)));
                  if (target.equals("IN_REPAIR")) return null;
                  stepAs(cursor, technician, () -> move(order, "TESTING"));
                  if (target.equals("TESTING")) return null;
                  stepAs(
                      cursor,
                      technician,
                      () -> finance.complete(order, Map.of("version", version(order))));
                  BigDecimal total = Input.decimal(finance.balance(order).get("charged"));
                  BigDecimal first = total.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                  UUID payment =
                      id(
                          step(
                              cursor,
                              () ->
                                  finance.payment(
                                      order,
                                      fields(
                                          "amount",
                                          first,
                                          "method",
                                          n % 2 == 0 ? "CASH" : "CARD",
                                          "comment",
                                          "Частичная оплата"))));
                  if (target.equals("COMPLETED")) return null;
                  step(cursor, () -> move(order, "READY_FOR_PICKUP"));
                  if (target.equals("READY_FOR_PICKUP")) return null;
                  step(
                      cursor,
                      () ->
                          finance.payment(
                              order,
                              fields(
                                  "amount",
                                  total.subtract(first),
                                  "method",
                                  "CARD",
                                  "comment",
                                  "Окончательный расчёт")));
                  step(cursor, () -> finance.deliver(order, Map.of("version", version(order))));
                  if (n == 16)
                    actors.as(
                        owner,
                        () -> {
                          step(
                              cursor,
                              () ->
                                  finance.adjustment(
                                      order,
                                      fields(
                                          "amount",
                                          "-500.00",
                                          "reason",
                                          "Согласованная скидка после ремонта")));
                          step(
                              cursor,
                              () ->
                                  finance.refund(
                                      payment,
                                      fields(
                                          "amount",
                                          500,
                                          "reason",
                                          "Возврат согласованной скидки")));
                          return null;
                        });
                  return null;
                });
          }
          for (int i = 0; i < 25; i++) {
            final int n = i;
            time.at(
                base.minus(Duration.ofDays(i)),
                () -> {
                  String[] categories = {"RENT", "UTILITIES", "TOOLS", "MAINTENANCE", "OTHER"};
                  if (n < 2) {
                    UUID purchase = purchaseIds.get(n == 0 ? 3 : 8);
                    finance.expense(
                        fields(
                            "amount",
                            purchases.details(purchase).get("total"),
                            "category",
                            "PARTS_PURCHASE",
                            "description",
                            "Оплата поставщику по закупке",
                            "purchase_order_id",
                            purchase));
                  } else
                    finance.expense(
                        fields(
                            "amount",
                            5000 + n * 1200,
                            "category",
                            categories[n % 5],
                            "description",
                            switch (n % 5) {
                              case 0 -> "Аренда помещения";
                              case 1 -> "Коммунальные услуги";
                              case 2 -> "Приобретение инструмента";
                              case 3 -> "Обслуживание оборудования";
                              default -> "Хозяйственные расходы";
                            }));
                  return null;
                });
          }
          verifier.verify();
          db.execute(
              "insert into seed_run(dataset_version,base_time,created_at) values"
                  + " ('workshop-v1',?,?)",
              base,
              Instant.now());
          return null;
        });
  }

  private int age(int i) {
    return i < 8 ? 180 - i * 3 : i < 16 ? 95 - (i - 8) : i < 24 ? 20 - (i - 16) : 1 + (i % 14);
  }

  private record Scenario(String failure, String work, String category) {}

  private Scenario scenario(int n) {
    int round = n / 8;
    return switch (n % 8) {
      case 0 ->
          switch (round % 5) {
            case 0 -> new Scenario("Аккумулятор не держит заряд", "Замена аккумулятора", "BATTERY");
            case 1 -> new Scenario("Ошибки чтения накопителя", "Замена SSD", "STORAGE");
            case 2 -> new Scenario("Перегрев под нагрузкой", "Замена термопасты", "TOOLS");
            case 3 -> new Scenario("Не работают клавиши", "Замена клавиатуры", "KEYBOARD");
            default ->
                new Scenario(
                    "Операционная система не загружается", "Установка операционной системы", null);
          };
      case 1 ->
          round % 2 == 0
              ? new Scenario(
                  "Шум вентилятора и перегрев", "Очистка и замена вентилятора", "COOLING")
              : new Scenario("Не определяется системный накопитель", "Замена SSD", "STORAGE");
      case 2 ->
          round % 2 == 0
              ? new Scenario("Повреждён экран смартфона", "Замена дисплея", "DISPLAY")
              : new Scenario("Смартфон быстро разряжается", "Замена аккумулятора", "BATTERY");
      case 3 ->
          round % 2 == 0
              ? new Scenario("Повреждён экран планшета", "Замена дисплея", "DISPLAY")
              : new Scenario(
                  "Нестабильная зарядка планшета", "Ремонт разъёма питания", "CONNECTOR");
      case 4 -> new Scenario("Монитор не включается", "Ремонт платы питания", "MOTHERBOARD");
      case 5 -> new Scenario("Принтер не получает питание", "Ремонт разъёма питания", "CONNECTOR");
      case 6 -> new Scenario("Консоль перегревается и выключается", "Замена термопасты", "TOOLS");
      default ->
          new Scenario(
              "Аудиосистема выключается при движении кабеля",
              "Ремонт разъёма питания",
              "CONNECTOR");
    };
  }

  private <T> T stepAs(Cursor cursor, UUID actor, Supplier<T> task) {
    return actors.as(actor, () -> step(cursor, task));
  }

  private UUID user(String login) {
    return Input.id(
        db.one("select id from app_user where email=?", login + "@fixflow.test").get("id"));
  }

  private UUID id(Map<String, Object> row) {
    return Input.id(row.get("id"));
  }

  private Object version(UUID id) {
    return db.get("repair_order", id).get("version");
  }

  private Map<String, Object> move(UUID id, String status) {
    return repairs.status(id, Map.of("status", status, "version", version(id)));
  }

  private static class Cursor {
    Instant value;

    Cursor(Instant value) {
      this.value = value;
    }
  }

  private <T> T step(Cursor cursor, Supplier<T> task) {
    Instant value = cursor.value;
    cursor.value = value.plusSeconds(300);
    return time.at(value, task);
  }

  private String target(int i) {
    if (i < 24) return "DELIVERED";
    if (i < 32) return "RECEIVED";
    if (i < 38) return "DIAGNOSIS";
    if (i < 44) return "WAITING_FOR_APPROVAL";
    if (i < 50) return "WAITING_FOR_PARTS";
    if (i < 58) return "IN_REPAIR";
    if (i < 64) return "TESTING";
    if (i < 70) return "COMPLETED";
    if (i < 76) return "READY_FOR_PICKUP";
    return "CANCELLED";
  }
}
