package com.fixflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fixflow.catalog.CatalogService;
import com.fixflow.common.*;
import com.fixflow.repair.RepairService;
import com.fixflow.security.Actors;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.profiles.active=test",
      "fixflow.demo-enabled=false",
      "fixflow.bootstrap-owner.enabled=false",
      "server.servlet.session.cookie.secure=false"
    })
@AutoConfigureMockMvc
class BackendApplicationIT {
  static PostgreSQLContainer<?> database;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    String externalUrl = System.getenv("FIXFLOW_TEST_DATABASE_URL");
    if (externalUrl == null || externalUrl.isBlank()) {
      database = new PostgreSQLContainer<>("postgres:18.6");
      database.start();
      properties.add("spring.datasource.url", database::getJdbcUrl);
      properties.add("spring.datasource.username", database::getUsername);
      properties.add("spring.datasource.password", database::getPassword);
    } else {
      properties.add("spring.datasource.url", () -> externalUrl);
      properties.add(
          "spring.datasource.username", () -> System.getenv("FIXFLOW_TEST_DATABASE_USER"));
      properties.add(
          "spring.datasource.password", () -> System.getenv("FIXFLOW_TEST_DATABASE_PASSWORD"));
    }
  }

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired Database db;
  @Autowired PasswordEncoder passwords;
  @Autowired CatalogService catalogs;
  @Autowired RepairService repairs;
  @Autowired Actors actors;
  @Autowired com.fixflow.inventory.InventoryService inventory;
  @Autowired com.fixflow.finance.FinanceService finance;
  @Autowired com.fixflow.procurement.ProcurementService purchases;
  @Autowired Idempotency idem;
  @Autowired com.fixflow.report.ReportService reports;
  @Autowired com.fixflow.tracking.TrackingController tracking;
  @Autowired com.fixflow.notification.NotificationService notifications;

  UUID fixtureUser(String role) {
    UUID id = UUID.randomUUID();
    db.insert(
        "app_user",
        Database.fields(
            "id",
            id,
            "email",
            id + "@example.test",
            "full_name",
            "Test " + role,
            "password_hash",
            passwords.encode("Test-password-123!"),
            "role",
            role,
            "created_at",
            java.time.Instant.now(),
            "updated_at",
            java.time.Instant.now()));
    return id;
  }

  @Test
  void realLoginRequiresCsrfAndLogoutRevokesTheSession() throws Exception {
    UUID id = fixtureUser("MANAGER");
    String email = id + "@example.test";
    mvc.perform(
            post("/api/v1/auth/login")
                .param("email", email)
                .param("password", "Test-password-123!"))
        .andExpect(status().isForbidden());
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("email", email)
                    .param("password", "Test-password-123!"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("MANAGER"))
            .andExpect(jsonPath("$.password_hash").doesNotExist())
            .andReturn();
    var cookie = login.getResponse().getCookie("SESSION");
    assertThat(cookie).isNotNull();
    mvc.perform(get("/api/v1/auth/me").cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email));
    mvc.perform(post("/api/v1/auth/logout").cookie(cookie).with(csrf()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
  }

  @Test
  void orderOwnershipAssignmentHistoryAndStaleUpdatesAreEnforced() {
    UUID manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        other = fixtureUser("TECHNICIAN");
    actors.as(
        manager,
        () -> {
          var customer =
              catalogs.save(
                  "customers",
                  null,
                  Map.of("full_name", "Синтетический клиент", "phone", "+77000000111"));
          UUID customerId = Input.id(customer.get("id"));
          var device =
              catalogs.save(
                  "devices",
                  null,
                  Map.of(
                      "customer_id",
                      customerId,
                      "type",
                      "LAPTOP",
                      "manufacturer",
                      "Test",
                      "model",
                      "Model"));
          UUID deviceId = Input.id(device.get("id"));
          var second =
              catalogs.save(
                  "customers", null, Map.of("full_name", "Другой клиент", "phone", "+77000000222"));
          assertThrows(
              ApiException.class,
              () ->
                  repairs.create(
                      Map.of(
                          "customer_id",
                          second.get("id"),
                          "device_id",
                          deviceId,
                          "problem",
                          "Failure")));
          var order =
              repairs.create(
                  Map.of(
                      "customer_id",
                      customerId,
                      "device_id",
                      deviceId,
                      "technician_id",
                      tech,
                      "problem",
                      "No power",
                      "estimated_total",
                      "10000"));
          UUID orderId = Input.id(order.get("id"));
          actors.as(
              other,
              () -> {
                assertThrows(ApiException.class, () -> repairs.details(orderId));
                return null;
              });
          actors.as(
              tech,
              () -> {
                repairs.status(orderId, Map.of("status", "DIAGNOSIS", "version", 0));
                return null;
              });
          assertThrows(
              ApiException.class,
              () ->
                  repairs.status(orderId, Map.of("status", "WAITING_FOR_APPROVAL", "version", 0)));
          assertThat((java.util.List<?>) repairs.details(orderId).get("history")).hasSize(2);
          return null;
        });
  }

  UUID repairInProgress(UUID manager, UUID technician) {
    return actors.as(
        manager,
        () -> {
          var customer =
              catalogs.save(
                  "customers",
                  null,
                  Map.of("full_name", "Fixture customer", "phone", "+77000000444"));
          var device =
              catalogs.save(
                  "devices",
                  null,
                  Map.of(
                      "customer_id",
                      customer.get("id"),
                      "type",
                      "LAPTOP",
                      "manufacturer",
                      "Fixture",
                      "model",
                      "Laptop"));
          var order =
              repairs.create(
                  Map.of(
                      "customer_id",
                      customer.get("id"),
                      "device_id",
                      device.get("id"),
                      "technician_id",
                      technician,
                      "problem",
                      "Does not start",
                      "estimated_total",
                      "20000"));
          UUID id = Input.id(order.get("id"));
          advance(id, "DIAGNOSIS");
          var diagnostic =
              repairs.diagnostic(
                  id,
                  Map.of(
                      "description",
                      "Power supply failed",
                      "result",
                      "Confirmed",
                      "comment",
                      "Service note"));
          assertThat(diagnostic.get("comment")).isEqualTo("Service note");
          advance(id, "WAITING_FOR_APPROVAL");
          repairs.approve(id, Map.of("amount", "20000", "comment", "Customer consent recorded"));
          advance(id, "IN_REPAIR");
          return id;
        });
  }

  void advance(UUID id, String status) {
    repairs.status(id, Map.of("status", status, "version", repairs.details(id).get("version")));
  }

  UUID part(UUID owner) {
    return actors.as(
        owner,
        () ->
            Input.id(
                catalogs
                    .save(
                        "parts",
                        null,
                        Map.of(
                            "name",
                            "Test SSD",
                            "sku",
                            "T-" + UUID.randomUUID(),
                            "category",
                            "STORAGE",
                            "purchase_price",
                            "100.00",
                            "sale_price",
                            "200.00",
                            "minimum_quantity",
                            "1"))
                    .get("id")));
  }

  @Test
  void completeRepairPartialPaymentDebtDeliveryAndRefundRemainConsistent() {
    UUID owner = fixtureUser("OWNER"),
        manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        part = part(owner),
        order = repairInProgress(manager, tech);
    actors.as(
        owner,
        () ->
            inventory.receipt(
                part, Map.of("quantity", 10, "unit_cost", 100, "reason", "Opening stock")));
    actors.as(
        tech,
        () -> {
          String key = UUID.randomUUID().toString();
          var body = Map.<String, Object>of("part_id", part, "quantity", 1);
          var first = idem.run("test-use/" + order, key, body, () -> inventory.use(order, body));
          var second = idem.run("test-use/" + order, key, body, () -> inventory.use(order, body));
          assertThat(second.get("id").toString()).isEqualTo(first.get("id").toString());
          assertThrows(
              ApiException.class,
              () ->
                  idem.run(
                      "test-use/" + order,
                      key,
                      Map.of("part_id", part, "quantity", 2),
                      () -> inventory.use(order, body)));
          return null;
        });
    assertThat(Input.decimal(db.get("spare_part", part).get("quantity"))).isEqualByComparingTo("9");
    actors.as(
        manager,
        () -> {
          repairs.work(order, Map.of("name", "Replace SSD", "quantity", 1, "unit_price", 100));
          advance(order, "TESTING");
          finance.complete(order, Map.of("version", repairs.details(order).get("version")));
          finance.payment(order, Map.of("amount", 100, "method", "CASH"));
          assertThat(Input.decimal(finance.balance(order).get("debt"))).isEqualByComparingTo("200");
          advance(order, "READY_FOR_PICKUP");
          assertThrows(
              ApiException.class,
              () ->
                  finance.deliver(order, Map.of("version", repairs.details(order).get("version"))));
          finance.payment(order, Map.of("amount", 200, "method", "CARD"));
          finance.deliver(order, Map.of("version", repairs.details(order).get("version")));
          assertThat(repairs.details(order).get("status")).isEqualTo("DELIVERED");
          assertThat(db.rows("select id from warranty where order_id=?", order)).hasSize(1);
          return null;
        });
    actors.as(
        owner,
        () -> {
          var payment = db.one("select * from payment where order_id=? and amount=200", order);
          assertThrows(
              ApiException.class,
              () ->
                  finance.refund(
                      Input.id(payment.get("id")),
                      Map.of("amount", 201, "reason", "Invalid refund")));
          finance.adjustment(order, Map.of("amount", "-50.00", "reason", "Agreed discount"));
          finance.refund(
              Input.id(payment.get("id")), Map.of("amount", 50, "reason", "Refund discount"));
          assertThat(Input.decimal(finance.balance(order).get("debt"))).isZero();
          assertThat(Input.decimal(finance.balance(order).get("credit"))).isZero();
          return null;
        });
  }

  @Test
  void cancellationPreservesHistoryAndRequiresAnExplicitDispositionOfUsedParts() {
    UUID owner = fixtureUser("OWNER"),
        manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        material = part(owner),
        order = repairInProgress(manager, tech);
    actors.as(
        owner,
        () ->
            inventory.receipt(
                material, Map.of("quantity", 2, "unit_cost", 100, "reason", "Opening")));
    var usage =
        actors.as(tech, () -> inventory.use(order, Map.of("part_id", material, "quantity", 1)));
    actors.as(
        manager,
        () -> {
          var cancel =
              Map.<String, Object>of(
                  "version",
                  repairs.details(order).get("version"),
                  "reason",
                  "Customer declined",
                  "keep_used_parts",
                  true);
          assertThrows(ApiException.class, () -> finance.cancel(order, cancel));
          assertThat(repairs.details(order).get("status")).isEqualTo("IN_REPAIR");
          inventory.returnUsage(
              order,
              Input.id(usage.get("id")),
              Map.of("quantity", 1, "reason", "Unused replacement returned"));
          finance.cancel(
              order,
              Map.of(
                  "version", repairs.details(order).get("version"), "reason", "Customer declined"));
          assertThat(repairs.details(order).get("status")).isEqualTo("CANCELLED");
          assertThat(db.rows("select id from warranty where order_id=?", order)).isEmpty();
          return null;
        });
    assertThat(Input.decimal(db.get("spare_part", material).get("quantity")))
        .isEqualByComparingTo("2");
    assertThat(db.rows("select id from inventory_transaction where order_id=?", order)).hasSize(2);
    assertThat(
            db.get("repair_part_usage", Input.id(usage.get("id")))
                .get("returned_quantity")
                .toString())
        .isEqualTo("1.000");
    UUID second = repairInProgress(manager, tech);
    actors.as(tech, () -> inventory.use(second, Map.of("part_id", material, "quantity", 1)));
    actors.as(
        owner,
        () ->
            finance.cancel(
                second,
                Map.of(
                    "version",
                    repairs.details(second).get("version"),
                    "reason",
                    "Consumed nonreturnable material",
                    "keep_used_parts",
                    true)));
    assertThat(Input.decimal(db.get("spare_part", material).get("quantity")))
        .isEqualByComparingTo("1");
    assertThat(db.rows("select id from inventory_transaction where order_id=?", second)).hasSize(1);
  }

  @Test
  void warrantyClaimKeepsOriginalHistoryAndCanReplaceAnInactiveTechnician() {
    UUID owner = fixtureUser("OWNER"),
        manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        replacement = fixtureUser("TECHNICIAN"),
        material = part(owner),
        original = repairInProgress(manager, tech);
    actors.as(
        owner,
        () ->
            inventory.receipt(
                material, Map.of("quantity", 1, "unit_cost", 100, "reason", "Opening")));
    UUID warranty =
        actors.as(
            manager,
            () -> {
              repairs.work(original, Map.of("name", "Repair", "quantity", 1, "unit_price", 100));
              advance(original, "TESTING");
              finance.complete(
                  original, Map.of("version", repairs.details(original).get("version")));
              finance.payment(original, Map.of("amount", 100, "method", "CASH"));
              advance(original, "READY_FOR_PICKUP");
              finance.deliver(
                  original, Map.of("version", repairs.details(original).get("version")));
              return Input.id(
                  db.one("select id from warranty where order_id=?", original).get("id"));
            });
    db.execute("update app_user set active=false where id=?", tech);
    UUID followup =
        actors.as(
            manager,
            () -> {
              String key = UUID.randomUUID().toString();
              var body = Map.<String, Object>of("description", "Recurring failure");
              var first =
                  idem.run(
                      "claim/" + warranty, key, body, () -> finance.warrantyClaim(warranty, body));
              var repeated =
                  idem.run(
                      "claim/" + warranty, key, body, () -> finance.warrantyClaim(warranty, body));
              assertThat(repeated.get("id").toString()).isEqualTo(first.get("id").toString());
              UUID id = Input.id(first.get("id"));
              var order = repairs.details(id);
              assertThat(order.get("technician_id")).isNull();
              assertThat(order.get("source_warranty_id")).isEqualTo(warranty);
              repairs.assign(
                  id, Map.of("version", order.get("version"), "technician_id", replacement));
              advance(id, "DIAGNOSIS");
              repairs.diagnostic(id, Map.of("description", "Covered by original warranty"));
              advance(id, "WAITING_FOR_APPROVAL");
              repairs.approve(id, Map.of("amount", 0, "comment", "Free warranty service"));
              advance(id, "IN_REPAIR");
              return id;
            });
    var usage =
        actors.as(
            replacement, () -> inventory.use(followup, Map.of("part_id", material, "quantity", 1)));
    assertThat(Input.decimal(usage.get("unit_sale_price"))).isZero();
    assertThat(Input.decimal(usage.get("total_cost"))).isEqualByComparingTo("100");
    assertThat(db.get("repair_order", original).get("status")).isEqualTo("DELIVERED");
    assertThat(db.rows("select id from warranty_claim where warranty_id=?", warranty)).hasSize(1);
    db.execute("update warranty set revoked_at=current_timestamp where id=?", warranty);
    actors.as(
        manager,
        () -> {
          assertThrows(
              ApiException.class,
              () -> finance.warrantyClaim(warranty, Map.of("description", "Revoked")));
          return null;
        });
    assertThat(db.rows("select id from warranty_claim where warranty_id=?", warranty)).hasSize(1);
  }

  @Test
  void concurrentLastPartUseHasExactlyOneWinner() throws Exception {
    UUID owner = fixtureUser("OWNER"),
        manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        part = part(owner),
        one = repairInProgress(manager, tech),
        two = repairInProgress(manager, tech);
    actors.as(
        owner,
        () ->
            inventory.receipt(
                part, Map.of("quantity", 1, "unit_cost", 100, "reason", "Single part")));
    var start = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var tasks =
          java.util.List.of(one, two).stream()
              .map(
                  order ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              actors.as(
                                  tech,
                                  () ->
                                      inventory.use(order, Map.of("part_id", part, "quantity", 1)));
                              return true;
                            } catch (ApiException e) {
                              assertThat(e.status()).isEqualTo(409);
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      int successes = 0;
      for (var task : tasks) if (task.get(15, java.util.concurrent.TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
    assertThat(Input.decimal(db.get("spare_part", part).get("quantity"))).isZero();
    assertThat(db.rows("select id from repair_part_usage where part_id=?", part)).hasSize(1);
  }

  @Test
  void failedCommandRollsBackStockMovementAndIdempotencyRecord() {
    UUID owner = fixtureUser("OWNER"), part = part(owner);
    actors.as(
        owner,
        () -> {
          String key = UUID.randomUUID().toString();
          assertThrows(
              ApiException.class,
              () ->
                  idem.run(
                      "rollback",
                      key,
                      Map.of(),
                      () -> {
                        inventory.receipt(
                            part,
                            Map.of("quantity", 3, "unit_cost", 100, "reason", "Should rollback"));
                        throw ApiException.conflict("Simulated failure");
                      }));
          assertThat(Input.decimal(db.get("spare_part", part).get("quantity"))).isZero();
          assertThat(db.rows("select id from inventory_transaction where part_id=?", part))
              .isEmpty();
          assertThat(db.rows("select id from idempotency_record where request_key=?", key))
              .isEmpty();
          return null;
        });
  }

  @Test
  void purchaseReceiptsThreePlusTwoAndRetryNeverOverReceive() {
    UUID owner = fixtureUser("OWNER"), part = part(owner);
    actors.as(
        owner,
        () -> {
          var supplier = catalogs.save("suppliers", null, Map.of("name", "Fixture supplier"));
          var purchase = purchases.create(Map.of("supplier_id", supplier.get("id")));
          UUID id = Input.id(purchase.get("id"));
          purchases.item(id, null, Map.of("part_id", part, "quantity", 5, "unit_price", 100));
          purchases.order(id);
          var item = purchases.items(id).getFirst();
          var body =
              Map.<String, Object>of(
                  "items", java.util.List.of(Map.of("item_id", item.get("id"), "quantity", 3)));
          String key = UUID.randomUUID().toString();
          idem.run("purchase-test/" + id, key, body, () -> purchases.receive(id, body));
          idem.run("purchase-test/" + id, key, body, () -> purchases.receive(id, body));
          assertThat(Input.decimal(db.get("spare_part", part).get("quantity")))
              .isEqualByComparingTo("3");
          purchases.receive(
              id,
              Map.of("items", java.util.List.of(Map.of("item_id", item.get("id"), "quantity", 2))));
          assertThat(purchases.details(id).get("status")).isEqualTo("RECEIVED");
          assertThat(Input.decimal(db.get("spare_part", part).get("quantity")))
              .isEqualByComparingTo("5");
          assertThat(db.rows("select id from purchase_receipt where purchase_order_id=?", id))
              .hasSize(2);
          return null;
        });
  }

  @Test
  void trackingIsRevocableAndNeverExposesCustomerContactsOrInternalData() throws Exception {
    UUID manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        order = repairInProgress(manager, tech);
    var issued = actors.as(manager, () -> tracking.issue(order));
    String token = issued.get("token");
    assertThat(token).hasSize(43);
    assertThat(
            db.one("select token_hash from public_tracking_token where order_id=?", order)
                .get("token_hash"))
        .isEqualTo(Idempotency.sha256(token));
    mvc.perform(get(issued.get("path")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_REPAIR"))
        .andExpect(jsonPath("$.phone").doesNotExist())
        .andExpect(jsonPath("$.serial_number").doesNotExist())
        .andExpect(jsonPath("$.customer_id").doesNotExist())
        .andExpect(jsonPath("$.final_total").doesNotExist());
    actors.as(
        manager,
        () -> {
          tracking.revoke(order);
          return null;
        });
    mvc.perform(get(issued.get("path"))).andExpect(status().isNotFound());
  }

  @Test
  void notificationsArePrivateToTheirRecipient() {
    UUID manager = fixtureUser("MANAGER"),
        tech = fixtureUser("TECHNICIAN"),
        other = fixtureUser("TECHNICIAN");
    var note =
        actors.as(
            manager,
            () ->
                notifications.create(
                    Map.of("recipient_id", tech, "message", "Проверьте устройство")));
    UUID id = Input.id(note.get("id"));
    actors.as(
        other,
        () -> {
          assertThrows(ApiException.class, () -> notifications.read(id));
          return null;
        });
    actors.as(
        tech,
        () -> {
          assertThat(((Number) notifications.unread().get("unread")).intValue()).isEqualTo(1);
          notifications.read(id);
          assertThat(((Number) notifications.unread().get("unread")).intValue()).isZero();
          return null;
        });
  }

  @Test
  void operationListsSortBeforePagingAndRejectUntrustedSortExpressions() throws Exception {
    UUID recipient = fixtureUser("TECHNICIAN");
    String email = recipient + "@example.test";
    java.time.Instant base = java.time.Instant.parse("2026-01-01T00:00:00Z");
    for (int index : new int[] {2, 0, 1})
      db.insert(
          "notification",
          Database.fields(
              "recipient_id",
              recipient,
              "type",
              "CUSTOM",
              "message",
              "sort " + index,
              "created_at",
              base.plusSeconds(index)));
    mvc.perform(
            get("/api/v1/notifications")
                .with(user(email))
                .param("sort", "created_at")
                .param("direction", "asc")
                .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.content[0].message").value("sort 0"))
        .andExpect(jsonPath("$.content[1].message").value("sort 1"));
    mvc.perform(
            get("/api/v1/notifications")
                .with(user(email))
                .param("sort", "created_at")
                .param("direction", "desc")
                .param("size", "1")
                .param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].message").value("sort 1"));
    mvc.perform(get("/api/v1/notifications").with(user(email)).param("sort", "created_at;select 1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void financialReportsSeparateRevenueCashCostsAndOperatingExpenses() {
    UUID owner = fixtureUser("OWNER"), tech = fixtureUser("TECHNICIAN"), part = part(owner);
    var filters =
        Map.of(
            "from",
            java.time.LocalDate.now().minusYears(1).toString(),
            "to",
            java.time.LocalDate.now().plusYears(1).toString());
    actors.as(
        owner,
        () -> {
          var before = reports.financial(filters);
          UUID order = repairInProgress(owner, tech);
          inventory.receipt(part, Map.of("quantity", 1, "unit_cost", 100, "reason", "Stock"));
          inventory.use(order, Map.of("part_id", part, "quantity", 1));
          repairs.work(order, Map.of("name", "Replace part", "quantity", 1, "unit_price", 300));
          advance(order, "TESTING");
          finance.complete(order, Map.of("version", repairs.details(order).get("version")));
          var payment = finance.payment(order, Map.of("amount", 500, "method", "CASH"));
          advance(order, "READY_FOR_PICKUP");
          finance.deliver(order, Map.of("version", repairs.details(order).get("version")));
          finance.expense(
              Map.of("amount", 50, "category", "RENT", "description", "Operating expense"));
          finance.adjustment(order, Map.of("amount", "-25.00", "reason", "Discount"));
          finance.refund(
              Input.id(payment.get("id")), Map.of("amount", 25, "reason", "Discount refund"));
          var after = reports.financial(filters);
          assertThat(
                  Input.decimal(after.get("cash_received"))
                      .subtract(Input.decimal(before.get("cash_received"))))
              .isEqualByComparingTo("500");
          assertThat(
                  Input.decimal(after.get("revenue"))
                      .subtract(Input.decimal(before.get("revenue"))))
              .isEqualByComparingTo("475");
          assertThat(
                  Input.decimal(after.get("parts_cost"))
                      .subtract(Input.decimal(before.get("parts_cost"))))
              .isEqualByComparingTo("100");
          assertThat(
                  Input.decimal(after.get("gross_profit"))
                      .subtract(Input.decimal(before.get("gross_profit"))))
              .isEqualByComparingTo("375");
          assertThat(
                  Input.decimal(after.get("operating_result"))
                      .subtract(Input.decimal(before.get("operating_result"))))
              .isEqualByComparingTo("325");
          @SuppressWarnings("unchecked")
          var months = (java.util.List<Map<String, Object>>) after.get("monthly");
          assertThat(
                  months.stream()
                      .map(m -> Input.decimal(m.get("revenue")))
                      .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
              .isEqualByComparingTo(Input.decimal(after.get("revenue")));
          assertThat(
                  months.stream()
                      .map(m -> Input.decimal(m.get("cash_received")))
                      .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
              .isEqualByComparingTo(Input.decimal(after.get("cash_received")));
          return null;
        });
  }

  @Test
  void privilegedEndpointsUseTheStoredRoleAndDisabledAccountsAreBlocked() throws Exception {
    UUID tech = fixtureUser("TECHNICIAN");
    String email = tech + "@example.test";
    mvc.perform(get("/api/v1/users").with(user(email).roles("OWNER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/reports/financial").with(user(email).roles("OWNER")))
        .andExpect(status().isForbidden());
    db.execute("update app_user set active=false where id=?", tech);
    mvc.perform(get("/api/v1/auth/me").with(user(email))).andExpect(status().isForbidden());
  }

  @Test
  void beanValidationReturnsFieldErrorsAndFailedCreationDoesNotPersist() throws Exception {
    UUID manager = fixtureUser("MANAGER");
    long before = ((Number) db.one("select count(*) n from customer").get("n")).longValue();
    mvc.perform(
            post("/api/v1/customers")
                .with(user(manager + "@example.test"))
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"full_name\":\"Invalid"
                        + " fixture\",\"phone\":\"not-a-phone\",\"email\":\"invalid\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.phone").exists())
        .andExpect(jsonPath("$.fieldErrors.email").exists());
    assertThat(((Number) db.one("select count(*) n from customer").get("n")).longValue())
        .isEqualTo(before);
  }

  @Test
  void repeatedLoginFailuresAreLimitedEvenAcrossDifferentIps() throws Exception {
    String email = "missing-" + UUID.randomUUID() + "@example.test";
    boolean limited = false;
    for (int i = 1; i <= 40; i++) {
      final int index = i;
      int status =
          mvc.perform(
                  post("/api/v1/auth/login")
                      .with(csrf())
                      .with(
                          request -> {
                            request.setRemoteAddr("127.1.0." + index);
                            return request;
                          })
                      .param("email", email)
                      .param("password", "invalid-password"))
              .andReturn()
              .getResponse()
              .getStatus();
      if (status == 429) {
        limited = true;
        break;
      }
      assertThat(status).isEqualTo(401);
    }
    assertThat(limited).isTrue();
  }

  @Test
  void partialReturnsPreserveQuantityAndEveryCentOfOriginalCost() {
    UUID owner = fixtureUser("OWNER"),
        tech = fixtureUser("TECHNICIAN"),
        part = part(owner),
        order = repairInProgress(owner, tech);
    actors.as(
        owner,
        () -> {
          inventory.receipt(
              part, Map.of("quantity", 3, "unit_cost", "0.01", "reason", "First batch"));
          inventory.receipt(
              part, Map.of("quantity", 2, "unit_cost", "0.02", "reason", "Second batch"));
          var usage = inventory.use(order, Map.of("part_id", part, "quantity", 5));
          UUID usageId = Input.id(usage.get("id"));
          for (int i = 0; i < 3; i++)
            inventory.returnUsage(
                order, usageId, Map.of("quantity", 1, "reason", "Partial return"));
          inventory.returnUsage(order, usageId, Map.of("quantity", 2, "reason", "Final return"));
          assertThat(Input.decimal(db.get("spare_part", part).get("quantity")))
              .isEqualByComparingTo("5");
          assertThat(Input.decimal(db.get("spare_part", part).get("stock_value")))
              .isEqualByComparingTo("0.07");
          assertThrows(
              ApiException.class,
              () ->
                  inventory.returnUsage(
                      order, usageId, Map.of("quantity", 1, "reason", "Excess return")));
          return null;
        });
  }

  @Test
  void changingPasswordRevokesSessionAndPreservesPasswordWhitespace() throws Exception {
    UUID id = fixtureUser("MANAGER");
    String email = id + "@example.test";
    String next = "  new-password-with-spaces  ";
    var login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .param("email", email)
                    .param("password", "Test-password-123!"))
            .andExpect(status().isOk())
            .andReturn();
    var cookie = login.getResponse().getCookie("SESSION");
    mvc.perform(
            post("/api/v1/auth/change-password")
                .cookie(cookie)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"oldPassword\":\"Test-password-123!\",\"newPassword\":\"" + next + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .param("email", email)
                .param("password", "Test-password-123!"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login").with(csrf()).param("email", email).param("password", next))
        .andExpect(status().isOk());
  }

  @Test
  void customerDuplicateDetectionNormalizesPhoneFormatting() {
    UUID manager = fixtureUser("MANAGER");
    actors.as(
        manager,
        () -> {
          String digits =
              "+7700"
                  + String.format(
                      "%07d",
                      java.util.concurrent.ThreadLocalRandom.current()
                          .nextInt(1_000_000, 9_999_999));
          var first =
              catalogs.save(
                  "customers", null, Map.of("full_name", "Duplicate fixture one", "phone", digits));
          var second =
              catalogs.save(
                  "customers",
                  null,
                  Map.of(
                      "full_name",
                      "Duplicate fixture two",
                      "phone",
                      digits.substring(0, 5) + " " + digits.substring(5)));
          @SuppressWarnings("unchecked")
          var duplicates = (java.util.List<Map<String, Object>>) second.get("potential_duplicates");
          assertThat(duplicates.stream().map(row -> row.get("id"))).contains(first.get("id"));
          return null;
        });
  }

  @Test
  void malformedClientParametersReturnValidationErrorsInsteadOfInternalFailures() throws Exception {
    UUID manager = fixtureUser("MANAGER");
    String email = manager + "@example.test";
    mvc.perform(get("/api/v1/customers/not-a-uuid").with(user(email)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    mvc.perform(get("/api/v1/reports/repairs").param("from", "not-a-date").with(user(email)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cyrillicSearchIsCaseInsensitiveEvenInACLocaleDatabase() {
    UUID manager = fixtureUser("MANAGER");
    actors.as(
        manager,
        () -> {
          String name = "АЛИЯ " + UUID.randomUUID();
          var customer =
              catalogs.save("customers", null, Map.of("full_name", name, "phone", "+77000000777"));
          var result =
              catalogs.list("customers", Map.of("q", name.toLowerCase(java.util.Locale.ROOT)));
          assertThat(result.content().stream().map(row -> row.get("id")))
              .contains(customer.get("id"));
          return null;
        });
  }

  @Test
  void healthIncludesARealDatabaseConnection() {
    var response = http.getForEntity("/actuator/health", Map.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).containsEntry("status", "UP");
  }

  @Test
  void flywayHasAppliedTheTechnicalMigration() {
    assertThat(
            jdbc.queryForObject(
                "select value from application_metadata where name='application'", String.class))
        .isEqualTo("FixFlow");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success=false", Integer.class))
        .isZero();
  }

  @AfterAll
  static void stopDatabase() {
    if (database != null) database.stop();
  }
}
