package com.fixflow.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixflow.desktop.component.Forms;
import com.fixflow.desktop.controller.ShellController;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.*;

/** Explicit GUI verification against a running development backend; not a headless unit test. */
public final class GuiSmoke {
  private static volatile Throwable failure;
  private static Stage stage;
  private static Parent root;
  private static ShellController controller;
  private static final String PREFIX = "GUI Smoke " + UUID.randomUUID().toString().substring(0, 8);
  private static String customer;
  private static String device;
  private static String order;
  private static String part;

  public static void main(String[] args) throws Exception {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
    String password = System.getenv("FIXFLOW_DEMO_PASSWORD");
    if (password == null)
      throw new IllegalArgumentException("Set FIXFLOW_DEMO_PASSWORD for the dev GUI smoke test");
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> failure = error);
    var ready = new CountDownLatch(1);
    Platform.startup(
        () -> {
          Platform.setImplicitExit(false);
          ready.countDown();
        });
    if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("JavaFX did not start");
    try {
      login("owner", password);
      for (String name :
          List.of(
              "dashboard",
              "repair-orders",
              "customers",
              "devices",
              "technicians",
              "users",
              "parts",
              "inventory-transactions",
              "suppliers",
              "purchase-orders",
              "payments",
              "refunds",
              "expenses",
              "warranties",
              "audit-logs",
              "notifications",
              "reports-repairs",
              "reports-inventory",
              "reports-financial",
              "shop-settings")) view(name);
      view("payments");
      fx(
          () -> {
            var column =
                table().getColumns().stream()
                    .filter(c -> "created_at".equals(c.getUserData()))
                    .findFirst()
                    .orElseThrow();
            check(column.isSortable(), "Payment date sorting is unavailable");
            column.setSortType(TableColumn.SortType.ASCENDING);
            table().getSortOrder().setAll(column);
            return null;
          });
      awaitIdle();
      check(
          fx(
              () -> {
                var dates =
                    table().getItems().stream()
                        .map(r -> java.time.Instant.parse(r.path("created_at").asText()))
                        .toList();
                return dates.size() > 1 && dates.equals(dates.stream().sorted().toList());
              }),
          "Payment date sorting is incorrect");
      view("parts");
      fx(
          () -> {
            ((CheckBox) root.lookup("#low-stock")).fire();
            return null;
          });
      awaitIdle();
      check(
          fx(
              () ->
                  !table().getItems().isEmpty()
                      && table().getItems().stream()
                          .allMatch(
                              r ->
                                  r.path("quantity")
                                          .decimalValue()
                                          .compareTo(r.path("minimum_quantity").decimalValue())
                                      < 0)),
          "Low-stock filter is incorrect");
      view("repair-orders");
      fx(
          () -> {
            ((CheckBox) root.lookup("#overdue-only")).fire();
            return null;
          });
      awaitIdle();
      check(
          fx(
              () ->
                  !table().getItems().isEmpty()
                      && table().getItems().stream()
                          .noneMatch(
                              r ->
                                  Set.of("COMPLETED", "READY_FOR_PICKUP", "DELIVERED", "CANCELLED")
                                      .contains(r.path("status").asText()))),
          "Overdue filter includes finished repairs");
      view("customers");
      create(
          Map.of(
              "full_name",
              PREFIX,
              "phone",
              String.format(
                  Locale.ROOT, "+7700000%04d", ThreadLocalRandom.current().nextInt(10000)),
              "email",
              "gui-" + UUID.randomUUID() + "@example.test"));
      customer = row("full_name", PREFIX).path("id").asText();
      view("devices");
      create(
          Map.of(
              "customer_id",
              customer,
              "type",
              "LAPTOP",
              "manufacturer",
              "Demo",
              "model",
              PREFIX,
              "serial_number",
              PREFIX));
      device = row("model", PREFIX).path("id").asText();
      view("parts");
      create(
          Map.of(
              "name",
              PREFIX + " part",
              "sku",
              "GUI-" + UUID.randomUUID().toString().substring(0, 8),
              "category",
              "STORAGE",
              "purchase_price",
              "100",
              "sale_price",
              "200",
              "minimum_quantity",
              "1"));
      part = row("name", PREFIX + " part").path("id").asText();
      view("repair-orders");
      String technician =
          UUID.nameUUIDFromBytes(
                  "tech1@fixflow.test".getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString();
      create(
          Map.of(
              "customer_id",
              customer,
              "device_id",
              device,
              "technician_id",
              technician,
              "problem",
              PREFIX,
              "estimated_total",
              "5000"));
      order = row("problem", PREFIX).path("id").asText();
      System.out.println(
          "GUI owner: every screen loaded; customer, device and assigned order created through"
              + " forms");
      close();
      login("tech1", password);
      check(
          fx(() -> root.lookup("#nav-users") == null && root.lookup("#nav-payments") == null),
          "Technician menu leaks owner features");
      view("repair-orders");
      check(
          fx(
              () ->
                  table().getItems().stream()
                      .allMatch(r -> r.path("technician_id").asText().equals(technician))),
          "Technician sees another technician's order");
      openOrder();
      changeStatus("DIAGNOSIS");
      openOrder();
      fireDialog("order-diagnostics");
      fill(
          Map.of(
              "description",
              "GUI: power circuit diagnosed",
              "result",
              "Fault confirmed",
              "comment",
              "GUI: service note"));
      awaitIdle();
      openOrder();
      changeStatus("WAITING_FOR_APPROVAL");
      System.out.println(
          "GUI technician: assignment scope, diagnosis and controlled status changes verified");
      close();
      login("manager1", password);
      check(
          fx(() -> root.lookup("#nav-users") == null && root.lookup("#nav-customers") != null),
          "Manager menu is incorrect");
      view("repair-orders");
      openOrder();
      fireDialog("order-approvals");
      fill(Map.of("amount", "5000", "comment", "GUI: client consent recorded"));
      awaitIdle();
      for (String name :
          List.of(
              "customers",
              "devices",
              "parts",
              "payments",
              "warranties",
              "notifications",
              "reports-repairs",
              "reports-inventory")) view(name);
      view("parts");
      selectRow("id", part);
      fire(() -> (Button) root.lookup("#open-details"));
      await(() -> paneWith("action-receipts") != null, "Part card did not open");
      fire(() -> (Button) paneWith("action-receipts").lookup("#action-receipts"));
      fill(Map.of("quantity", "1", "unit_cost", "100,00", "reason", "GUI receipt"));
      awaitIdle();
      check(row("id", part).path("quantity").asDouble() == 1, "Receipt did not update stock");
      view("repair-orders");
      openOrder();
      changeStatus("IN_REPAIR");
      close();
      login("tech1", password);
      view("repair-orders");
      openOrder();
      fireDialog("order-parts");
      fill(Map.of("part_id", part, "quantity", "1"));
      awaitIdle();
      openOrder();
      fireDialog("order-works");
      fill(
          Map.of(
              "name",
              "GUI replacement",
              "quantity",
              "1",
              "description",
              "Replacement and functional test"));
      awaitIdle();
      openOrder();
      changeStatus("TESTING");
      close();
      login("manager1", password);
      view("repair-orders");
      openOrder();
      fx(
          () -> {
            var tabs = (TabPane) orderPane().lookup(".tab-pane");
            tabs.getSelectionModel()
                .select(
                    tabs.getTabs().stream()
                        .filter(t -> t.getText().equals("Работы"))
                        .findFirst()
                        .orElseThrow());
            return null;
          });
      await(() -> orderPane().lookup("#section-works") != null, "Work tab did not open");
      fx(
          () -> {
            ((TableView<?>) orderPane().lookup("#section-works")).getSelectionModel().selectFirst();
            return null;
          });
      fireDialog("price-work");
      fill(Map.of("unit_price", "1 000,00"));
      awaitIdle();
      close();
      login("tech1", password);
      view("repair-orders");
      openOrder();
      confirmOrderButton("Завершить ремонт");
      close();
      login("manager1", password);
      view("repair-orders");
      openOrder();
      fireDialog("order-payments");
      fill(Map.of("amount", "600,00", "method", "CASH"));
      awaitIdle();
      openOrder();
      changeStatus("READY_FOR_PICKUP");
      openOrder();
      fireOrderText("Выдать устройство");
      fill(Map.of());
      await(() -> errorPane() != null, "Delivery with debt was not rejected");
      check(
          fx(() -> errorPane().getContentText().contains("задолженность")), "Wrong debt rejection");
      Platform.runLater(() -> ((Button) errorPane().lookupButton(ButtonType.OK)).fire());
      await(() -> errorPane() == null, "Error dialog did not close");
      fireDialog("order-payments");
      fill(Map.of("amount", "600,00", "method", "CARD"));
      awaitIdle();
      openOrder();
      confirmOrderButton("Выдать устройство");
      check(
          row("id", order).path("status").asText().equals("DELIVERED"), "Order was not delivered");
      view("parts");
      check(row("id", part).path("quantity").asDouble() == 0, "Part was not consumed exactly once");
      view("warranties");
      check(
          fx(
              () ->
                  table().getItems().stream()
                      .anyMatch(r -> r.path("order_id").asText().equals(order))),
          "Warranty was not created");
      System.out.println(
          "GUI full repair: receipt, part use, work pricing, completion, debt rejection, payment,"
              + " delivery and warranty verified");
      System.out.println("GUI SMOKE SUCCESS: " + PREFIX + "; order=" + order);
    } finally {
      close();
      Platform.exit();
    }
    if (failure != null) throw new AssertionError("Uncaught JavaFX failure", failure);
  }

  private static void login(String login, String password) throws Exception {
    fx(
        () -> {
          var loader = new FXMLLoader(GuiSmoke.class.getResource("/fxml/start.fxml"));
          root = loader.load();
          controller = loader.getController();
          stage = new Stage();
          var scene = new Scene(root, 1280, 850);
          scene
              .getStylesheets()
              .add(GuiSmoke.class.getResource("/css/fixflow.css").toExternalForm());
          stage.setScene(scene);
          stage.setTitle("FixFlow GUI verification");
          stage.show();
          ((TextField) loader.getNamespace().get("email")).setText(login + "@fixflow.test");
          ((PasswordField) loader.getNamespace().get("password")).setText(password);
          return null;
        });
    fire(() -> (Button) root.lookup("#loginButton"));
    await(() -> root.lookup("#nav-dashboard") != null, "Login did not complete");
    awaitIdle();
  }

  private static void view(String name) throws Exception {
    fire(() -> (Button) root.lookup("#nav-" + name));
    awaitIdle();
    if (name.equals("dashboard") && System.getenv("FIXFLOW_GUI_SCREENSHOT") != null)
      screenshot(System.getenv("FIXFLOW_GUI_SCREENSHOT"));
    System.out.println("GUI view: " + name);
  }

  private static void create(Map<String, String> values) throws Exception {
    fire(() -> (Button) root.lookup("#create"));
    fill(values);
    awaitIdle();
  }

  private static void fill(Map<String, String> values) throws Exception {
    await(() -> form() != null, "Form did not open");
    fx(
        () -> {
          var pane = form();
          for (var entry : values.entrySet()) {
            Node node = pane.lookup("#" + entry.getKey());
            if (node instanceof TextInputControl text) text.setText(entry.getValue());
            else if (node instanceof ComboBox<?> combo) {
              Object match =
                  combo.getItems().stream()
                      .filter(
                          item ->
                              item instanceof Forms.Choice choice
                                  ? choice.id().equals(entry.getValue())
                                  : item.toString().equals(entry.getValue()))
                      .findFirst()
                      .orElseThrow(
                          () -> new IllegalStateException("Missing option: " + entry.getKey()));
              select(combo, match);
            } else throw new IllegalStateException("Missing input: " + entry.getKey());
          }
          return null;
        });
    Platform.runLater(() -> ((Button) form().lookupButton(ButtonType.OK)).fire());
    await(() -> form() == null, "Form was not submitted");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void select(ComboBox combo, Object value) {
    combo.setValue(value);
  }

  private static DialogPane form() {
    for (Window window : Window.getWindows())
      if (window.isShowing()
          && window.getScene() != null
          && window.getScene().getRoot() instanceof DialogPane pane
          && pane.getButtonTypes().contains(ButtonType.OK)) return pane;
    return null;
  }

  @SuppressWarnings("unchecked")
  private static TableView<JsonNode> table() {
    return (TableView<JsonNode>) root.lookup(".table-view");
  }

  private static JsonNode row(String field, String value) throws Exception {
    return fx(
        () ->
            table().getItems().stream()
                .filter(r -> r.path(field).asText().equals(value))
                .findFirst()
                .orElseThrow(
                    () -> new AssertionError("Saved row missing: " + field + "=" + value)));
  }

  private static void selectRow(String field, String value) throws Exception {
    var selected = row(field, value);
    fx(
        () -> {
          table().getSelectionModel().select(selected);
          return null;
        });
  }

  private static void openOrder() throws Exception {
    fx(
        () -> {
          var row =
              table().getItems().stream()
                  .filter(r -> r.path("id").asText().equals(order))
                  .findFirst()
                  .orElseThrow();
          table().getSelectionModel().select(row);
          return null;
        });
    fire(() -> (Button) root.lookup("#open-details"));
    await(() -> orderPane() != null, "Order details did not open");
  }

  private static DialogPane orderPane() {
    for (Window window : Window.getWindows())
      if (window.isShowing()
          && window.getScene() != null
          && window.getScene().getRoot() instanceof DialogPane pane
          && pane.lookup("#order-status") != null) return pane;
    return null;
  }

  private static DialogPane paneWith(String id) {
    for (Window window : Window.getWindows())
      if (window.isShowing()
          && window.getScene() != null
          && window.getScene().getRoot() instanceof DialogPane pane
          && pane.lookup("#" + id) != null) return pane;
    return null;
  }

  private static DialogPane errorPane() {
    for (Window window : Window.getWindows())
      if (window.isShowing()
          && window.getScene() != null
          && window.getScene().getRoot() instanceof DialogPane pane
          && pane.getHeaderText() != null
          && pane.getHeaderText().contains("Операция не выполнена")) return pane;
    return null;
  }

  private static void fireOrderText(String text) throws Exception {
    fire(
        () ->
            orderPane().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && b.getText().equals(text))
                .map(n -> (Button) n)
                .findFirst()
                .orElseThrow());
  }

  private static void confirmOrderButton(String text) throws Exception {
    fireOrderText(text);
    fill(Map.of());
    await(() -> orderPane() == null, "Order command did not finish");
    awaitIdle();
  }

  private static void screenshot(String path) throws Exception {
    fx(
        () -> {
          var image = root.snapshot(null, null);
          int width = (int) image.getWidth(), height = (int) image.getHeight();
          int[] pixels = new int[width * height];
          image
              .getPixelReader()
              .getPixels(
                  0,
                  0,
                  width,
                  height,
                  javafx.scene.image.PixelFormat.getIntArgbInstance(),
                  pixels,
                  0,
                  width);
          var output =
              new java.awt.image.BufferedImage(
                  width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
          output.setRGB(0, 0, width, height, pixels, 0, width);
          javax.imageio.ImageIO.write(output, "png", new java.io.File(path));
          return null;
        });
  }

  private static void changeStatus(String status) throws Exception {
    fx(
        () -> {
          select((ComboBox<?>) orderPane().lookup("#order-status"), status);
          return null;
        });
    fireDialog("order-status-apply");
    await(() -> orderPane() == null, "Status command did not complete");
    awaitIdle();
  }

  private static void fireDialog(String id) throws Exception {
    await(
        () -> orderPane() != null && orderPane().lookup("#" + id) != null,
        "Order action missing: " + id);
    fire(() -> (Button) orderPane().lookup("#" + id));
  }

  private static void fire(Supplier<Button> supplier) throws Exception {
    fx(
        () -> {
          var button = supplier.get();
          check(button != null && !button.isDisabled(), "Button not available");
          Platform.runLater(button::fire);
          return null;
        });
    Thread.sleep(100);
  }

  private static void awaitIdle() throws Exception {
    await(
        () -> {
          var indicator = root.lookup("#loading");
          return indicator != null && !indicator.isVisible() && form() == null;
        },
        "Request did not finish");
    Thread.sleep(100);
    checkErrors();
  }

  private static void checkErrors() throws Exception {
    fx(
        () -> {
          for (Window window : Window.getWindows())
            if (window.isShowing()
                && window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane pane
                && pane.getHeaderText() != null
                && pane.getHeaderText().contains("Операция не выполнена"))
              throw new AssertionError(pane.getContentText());
          return null;
        });
    if (failure != null) throw new AssertionError("JavaFX callback failed", failure);
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline) {
      if (failure != null) throw new AssertionError(message, failure);
      if (fx(condition::getAsBoolean)) return;
      Thread.sleep(80);
    }
    checkErrors();
    throw new AssertionError(message);
  }

  private static <T> T fx(Callable<T> task) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(task.call());
          } catch (Throwable e) {
            result.completeExceptionally(e);
          }
        });
    return result.get(10, TimeUnit.SECONDS);
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void close() throws Exception {
    if (stage == null) return;
    fx(
        () -> {
          if (controller != null) controller.stop();
          for (var window : new ArrayList<>(Window.getWindows())) window.hide();
          return null;
        });
    stage = null;
  }
}
