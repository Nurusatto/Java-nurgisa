package com.fixflow.desktop.controller;

import static com.fixflow.desktop.component.Forms.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixflow.desktop.api.*;
import com.fixflow.desktop.component.ExportService;
import com.fixflow.desktop.component.Forms;
import com.fixflow.desktop.component.Labels;
import com.fixflow.desktop.viewmodel.ListState;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;

public class ShellController {
  @FXML private BorderPane root;
  @FXML private VBox loginPane;
  @FXML private TextField email;
  @FXML private PasswordField password;
  @FXML private TextField server;
  @FXML private Label loginError;
  @FXML private Button loginButton;
  private FixFlowGateway gateway;
  private JsonNode user;
  private String resource = "repair-orders";
  private final ListState state = new ListState();
  private final BorderPane workspace = new BorderPane();
  private final TableView<JsonNode> table = new TableView<>();
  private final Label message = new Label();
  private final Label title = new Label();
  private final Label paging = new Label();
  private final ProgressIndicator progress = new ProgressIndicator();
  private Button next;
  private Button previous;
  private Button create;
  private Button edit;
  private Button detailsButton;
  private Button notificationsButton;
  private Button exportButton;
  private FlowPane periodPane;
  private javafx.animation.Timeline polling;
  private final DatePicker fromDate = new DatePicker(java.time.LocalDate.now().minusDays(29));
  private final DatePicker toDate = new DatePicker(java.time.LocalDate.now());
  private final ComboBox<String> statusFilter = new ComboBox<>();
  private final ComboBox<String> priorityFilter = new ComboBox<>();
  private final ComboBox<Forms.Choice> technicianFilter = new ComboBox<>();
  private final CheckBox lowStock = new CheckBox("Низкие остатки");
  private final CheckBox overdueOnly = new CheckBox("Просроченные");
  private int generation;
  private int requests;
  private boolean applyDates;

  @FXML
  private void initialize() {
    server.setText(System.getenv().getOrDefault("FIXFLOW_API_URL", "http://127.0.0.1:8080"));
  }

  @FXML
  private void login() {
    try {
      if (gateway != null) gateway.stop();
      gateway = new FixFlowGateway(new ApiClient(server.getText().trim()));
      loginButton.setDisable(true);
      loginError.setText("Вход…");
      gateway
          .login(email.getText().trim(), password.getText())
          .thenCompose(
              result ->
                  gateway
                      .settings()
                      .thenApply(
                          settings -> {
                            Forms.zone = java.time.ZoneId.of(settings.path("timezone").asText());
                            Labels.currency = settings.path("currency").asText();
                            return result;
                          }))
          .whenComplete(
              (result, error) ->
                  Platform.runLater(
                      () -> {
                        loginButton.setDisable(false);
                        if (error != null) {
                          loginError.setText(errorMessage(error));
                          return;
                        }
                        password.clear();
                        user = result;
                        buildShell();
                      }));
    } catch (Exception e) {
      loginButton.setDisable(false);
      loginError.setText(e.getMessage());
    }
  }

  private boolean manager() {
    return !user.path("role").asText().equals("TECHNICIAN");
  }

  private boolean owner() {
    return user.path("role").asText().equals("OWNER");
  }

  private void buildShell() {
    root.setCenter(workspace);
    priorityFilter.getItems().clear();
    priorityFilter.setValue(null);
    technicianFilter.getItems().clear();
    technicianFilter.setValue(null);
    var sidebar = new VBox(10);
    sidebar.getStyleClass().add("sidebar");
    var brand = new Label("FixFlow");
    brand.getStyleClass().add("sidebar-brand");
    sidebar
        .getChildren()
        .addAll(
            brand,
            new Label(user.path("name").asText() + "\n" + Labels.text(user.path("role").asText())));
    nav(sidebar, "Dashboard", "dashboard");
    nav(sidebar, "Заказы", "repair-orders");
    if (manager()) {
      nav(sidebar, "Клиенты", "customers");
      nav(sidebar, "Устройства", "devices");
    }
    nav(sidebar, "Мастера", "technicians");
    nav(sidebar, "Запчасти", "parts");
    if (manager()) {
      nav(sidebar, "Движения склада", "inventory-transactions");
      nav(sidebar, "Поставщики", "suppliers");
      nav(sidebar, "Платежи", "payments");
      nav(sidebar, "Гарантии", "warranties");
    }
    if (owner()) {
      nav(sidebar, "Закупки", "purchase-orders");
      nav(sidebar, "Расходы", "expenses");
      nav(sidebar, "Возвраты денег", "refunds");
      nav(sidebar, "Сотрудники", "users");
      nav(sidebar, "Аудит", "audit-logs");
    }
    notificationsButton = nav(sidebar, "Уведомления", "notifications");
    nav(sidebar, "Отчёт по ремонтам", "reports-repairs");
    if (manager()) nav(sidebar, "Отчёт по складу", "reports-inventory");
    if (owner()) {
      nav(sidebar, "Финансовый отчёт", "reports-financial");
      nav(sidebar, "Настройки", "shop-settings");
    }
    var changePassword = new Button("Сменить пароль");
    changePassword.setOnAction(
        e ->
            Forms.show(
                    "Смена пароля",
                    List.of(
                        typed("oldPassword", "Текущий пароль", "password", true),
                        typed("newPassword", "Новый пароль", "password", true)),
                    null,
                    Map.of())
                .ifPresent(values -> run(gateway.changePassword(values), ignored -> signedOut())));
    sidebar.getChildren().add(changePassword);
    var logout = new Button("Выйти");
    logout.setOnAction(e -> run(gateway.logout(), ignored -> signedOut()));
    sidebar.getChildren().add(logout);
    var menu = new ScrollPane(sidebar);
    menu.setFitToWidth(true);
    menu.setPrefWidth(255);
    root.setLeft(menu);
    title.getStyleClass().add("page-title");
    var search = new TextField();
    search.setId("search");
    search.setPromptText("Поиск…");
    search.setOnAction(
        e -> {
          state.search(search.getText());
          load();
        });
    var find = new Button("Найти");
    find.setId("find");
    find.setOnAction(
        e -> {
          state.search(search.getText());
          load();
        });
    create = new Button("Создать");
    create.setId("create");
    create.getStyleClass().add("primary");
    create.setOnAction(e -> form(null));
    edit = new Button("Изменить");
    edit.setOnAction(
        e -> {
          if (resource.equals("shop-settings")) {
            editSettings();
            return;
          }
          var row = table.getSelectionModel().getSelectedItem();
          if (row != null) form(row);
          else message.setText("Выберите запись");
        });
    detailsButton = new Button("Открыть");
    detailsButton.setId("open-details");
    detailsButton.setOnAction(e -> openSelected());
    var refresh = new Button("Обновить");
    refresh.setOnAction(e -> load());
    var export = new Button("Экспорт CSV");
    export.setOnAction(e -> exportCsv());
    statusFilter.setPromptText("Статус / категория");
    statusFilter.setOnAction(
        e -> {
          state.status(statusFilter.getValue() == null ? "" : statusFilter.getValue());
          load();
        });
    priorityFilter.getItems().addAll("", "LOW", "NORMAL", "HIGH", "URGENT");
    priorityFilter.setPromptText("Приоритет");
    priorityFilter.setOnAction(e -> load());
    technicianFilter.setPromptText("Мастер");
    technicianFilter.setOnAction(e -> load());
    lowStock.setId("low-stock");
    lowStock.setOnAction(e -> load());
    overdueOnly.setId("overdue-only");
    overdueOnly.setOnAction(e -> load());
    var range = new ComboBox<String>();
    range.getItems().addAll("Сегодня", "7 дней", "30 дней", "Текущий месяц", "Произвольный период");
    range.setValue("30 дней");
    range.setOnAction(
        e -> {
          var today = java.time.LocalDate.now(Forms.zone);
          toDate.setValue(today);
          fromDate.setValue(
              switch (range.getValue()) {
                case "Сегодня" -> today;
                case "7 дней" -> today.minusDays(6);
                case "Текущий месяц" -> today.withDayOfMonth(1);
                default -> today.minusDays(29);
              });
        });
    var dates = new Button("Применить период");
    dates.setOnAction(
        e -> {
          applyDates = true;
          load();
        });
    exportButton = export;
    var actions =
        new FlowPane(
            10,
            10,
            search,
            find,
            statusFilter,
            lowStock,
            overdueOnly,
            priorityFilter,
            technicianFilter,
            create,
            edit,
            detailsButton,
            refresh,
            export);
    periodPane = new FlowPane(10, 10, range, fromDate, toDate, dates);
    var header = new VBox(14, title, actions, periodPane);
    header.getStyleClass().add("page-header");
    workspace.setTop(header);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(new Label("Записей нет"));
    table.setRowFactory(
        t -> {
          var row = new TableRow<JsonNode>();
          row.setOnMouseClicked(
              e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) openSelected();
              });
          return row;
        });
    workspace.setCenter(table);
    table.setSortPolicy(
        view -> {
          if (view.getSortOrder().isEmpty()) return true;
          var column = view.getSortOrder().getFirst();
          state.sort(
              column.getUserData().toString(),
              column.getSortType() == TableColumn.SortType.ASCENDING ? "asc" : "desc");
          load();
          return true;
        });
    previous = new Button("←");
    previous.setOnAction(
        e -> {
          state.previous();
          load();
        });
    next = new Button("→");
    next.setOnAction(
        e -> {
          state.next();
          load();
        });
    progress.setId("loading");
    progress.setPrefSize(20, 20);
    progress.setVisible(false);
    var footer = new HBox(12, previous, paging, next, progress, message);
    footer.getStyleClass().add("footer");
    workspace.setBottom(footer);
    show("dashboard", "Dashboard");
    gateway
        .list("technicians", Map.of("size", "100"))
        .thenAccept(
            result ->
                Platform.runLater(
                    () -> {
                      technicianFilter.getItems().clear();
                      technicianFilter.getItems().add(new Forms.Choice("", "Все мастера"));
                      result
                          .path("content")
                          .forEach(
                              row ->
                                  technicianFilter
                                      .getItems()
                                      .add(
                                          new Forms.Choice(
                                              row.path("id").asText(),
                                              row.path("full_name").asText())));
                    }));
    if (polling != null) polling.stop();
    polling =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.seconds(20), e -> refreshUnread()));
    polling.setCycleCount(javafx.animation.Animation.INDEFINITE);
    polling.play();
    refreshUnread();
  }

  private Button nav(VBox sidebar, String label, String name) {
    var button = new Button(label);
    button.setId("nav-" + name);
    button.setMaxWidth(Double.MAX_VALUE);
    button.setOnAction(e -> show(name, label));
    sidebar.getChildren().add(button);
    return button;
  }

  private void show(String name, String label) {
    resource = name;
    lowStock.setSelected(false);
    overdueOnly.setSelected(false);
    boolean stockPage = name.equals("parts") || name.equals("reports-inventory");
    lowStock.setVisible(stockPage);
    lowStock.setManaged(stockPage);
    overdueOnly.setVisible(name.equals("repair-orders"));
    overdueOnly.setManaged(overdueOnly.isVisible());
    title.setText(label);
    state.reset();
    applyDates = false;
    table.getItems().clear();
    configureColumns();
    boolean special =
        name.equals("dashboard") || name.startsWith("reports-") || name.equals("shop-settings");
    boolean writable =
        Set.of("customers", "devices", "repair-orders", "notifications").contains(name)
            ? manager()
            : owner()
                && Set.of("users", "parts", "suppliers", "purchase-orders", "expenses")
                    .contains(name);
    create.setVisible(writable);
    create.setManaged(writable);
    boolean editable =
        writable && !Set.of("purchase-orders", "expenses", "notifications").contains(name)
            || name.equals("shop-settings") && owner();
    edit.setVisible(editable);
    edit.setManaged(editable);
    previous.setVisible(!special);
    previous.setManaged(!special);
    next.setVisible(!special);
    next.setManaged(!special);
    paging.setVisible(!special);
    paging.setManaged(!special);
    detailsButton.setVisible(!special);
    detailsButton.setManaged(!special);
    workspace.setCenter(table);
    statusFilter.getItems().clear();
    var statuses =
        switch (name) {
          case "repair-orders", "reports-repairs" ->
              List.of(
                  "",
                  "RECEIVED",
                  "DIAGNOSIS",
                  "WAITING_FOR_APPROVAL",
                  "WAITING_FOR_PARTS",
                  "IN_REPAIR",
                  "TESTING",
                  "COMPLETED",
                  "READY_FOR_PICKUP",
                  "DELIVERED",
                  "CANCELLED");
          case "parts", "reports-inventory" ->
              List.of(
                  "",
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
                  "OTHER");
          case "purchase-orders" ->
              List.of("", "DRAFT", "ORDERED", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED");
          case "warranties" -> List.of("", "ACTIVE", "EXPIRING", "EXPIRED");
          case "notifications" ->
              List.of(
                  "",
                  "CUSTOM",
                  "ORDER_ASSIGNED",
                  "STATUS_CHANGED",
                  "ORDER_DUE_SOON",
                  "ORDER_OVERDUE",
                  "LOW_STOCK",
                  "OUT_OF_STOCK",
                  "READY_FOR_PICKUP",
                  "WARRANTY_CREATED",
                  "PURCHASE_RECEIVED");
          case "inventory-transactions" ->
              List.of(
                  "", "RECEIPT", "REPAIR_USE", "REPAIR_RETURN", "PURCHASE_RECEIPT", "ADJUSTMENT");
          default -> List.<String>of();
        };
    statusFilter.getItems().addAll(statuses);
    statusFilter.setVisible(!statuses.isEmpty());
    statusFilter.setManaged(!statuses.isEmpty());
    boolean orders = name.equals("repair-orders") || name.equals("reports-repairs");
    priorityFilter.setVisible(orders);
    priorityFilter.setManaged(orders);
    technicianFilter.setVisible(orders && manager());
    technicianFilter.setManaged(orders && manager());
    boolean datesVisible =
        special && !name.equals("shop-settings")
            || Set.of(
                    "repair-orders",
                    "inventory-transactions",
                    "purchase-orders",
                    "payments",
                    "refunds",
                    "expenses",
                    "notifications",
                    "audit-logs")
                .contains(name);
    periodPane.setVisible(datesVisible);
    periodPane.setManaged(datesVisible);
    exportButton.setVisible(!name.equals("dashboard") && !name.equals("shop-settings"));
    exportButton.setManaged(exportButton.isVisible());
    for (String key : List.of("search", "find")) {
      var node = workspace.getTop().lookup("#" + key);
      if (node != null) {
        node.setVisible(!name.equals("dashboard") && !name.equals("shop-settings"));
        node.setManaged(node.isVisible());
      }
    }
    load();
  }

  private void configureColumns() {
    table.getColumns().clear();
    String[] columns =
        switch (resource) {
          case "repair-orders" ->
              new String[] {
                "order_number",
                "customer_name",
                "device_model",
                "status",
                "priority",
                "technician_name",
                "due_at",
                "estimated_total",
                "final_total"
              };
          case "customers" -> new String[] {"full_name", "phone", "email", "active"};
          case "devices" -> new String[] {"type", "manufacturer", "model", "serial_number"};
          case "parts" ->
              new String[] {
                "sku",
                "name",
                "category",
                "quantity",
                "minimum_quantity",
                "sale_price",
                "storage_location"
              };
          case "suppliers" -> new String[] {"name", "contact_name", "phone", "email", "active"};
          case "purchase-orders" ->
              new String[] {
                "number", "supplier_name", "status", "total", "expected_at", "created_at"
              };
          case "payments" ->
              new String[] {"order_number", "amount", "method", "status", "created_at"};
          case "refunds" -> new String[] {"amount", "reason", "created_at"};
          case "expenses" -> new String[] {"amount", "category", "description", "created_at"};
          case "warranties" ->
              new String[] {"number", "order_number", "starts_on", "ends_on", "terms"};
          case "inventory-transactions" ->
              new String[] {
                "sku",
                "part_name",
                "type",
                "direction",
                "quantity",
                "after_quantity",
                "reason",
                "created_at"
              };
          case "notifications" -> new String[] {"type", "message", "created_at", "read_at"};
          case "audit-logs" -> new String[] {"action", "entity_type", "description", "occurred_at"};
          default -> new String[] {"full_name", "email", "role", "active"};
        };
    Set<String> sortable =
        switch (resource) {
          case "repair-orders" -> Set.of("order_number", "status", "priority", "due_at");
          case "customers", "users", "technicians" -> Set.of("full_name", "email");
          case "devices" -> Set.of("manufacturer", "model");
          case "parts" -> Set.of("sku", "name", "category", "quantity");
          case "suppliers" -> Set.of("name", "email");
          case "purchase-orders" ->
              Set.of("number", "status", "total", "expected_at", "created_at");
          case "payments" -> Set.of("amount", "method", "status", "created_at");
          case "refunds" -> Set.of("amount", "created_at");
          case "expenses" -> Set.of("amount", "category", "created_at");
          case "warranties" -> Set.of("number", "starts_on", "ends_on");
          case "inventory-transactions" -> Set.of("type", "direction", "quantity", "created_at");
          case "notifications" -> Set.of("type", "created_at", "read_at");
          case "audit-logs" -> Set.of("action", "entity_type", "occurred_at");
          default -> Set.of();
        };
    for (String key : columns) {
      var column = new TableColumn<JsonNode, String>(label(key));
      column.setUserData(key);
      column.setCellValueFactory(
          data -> new SimpleStringProperty(Labels.value(data.getValue(), key)));
      column.setSortable(sortable.contains(key));
      table.getColumns().add(column);
    }
  }

  private Map<String, String> filters() {
    var filters = new LinkedHashMap<>(state.filters());
    if (lowStock.isSelected() && (resource.equals("parts") || resource.equals("reports-inventory")))
      filters.put("low_stock", "true");
    if (overdueOnly.isSelected() && resource.equals("repair-orders"))
      filters.put("overdue", "true");
    if (resource.equals("parts") || resource.equals("reports-inventory")) {
      String category = filters.remove("status");
      if (category != null) filters.put("category", category);
    }
    if (resource.equals("notifications") || resource.equals("inventory-transactions")) {
      String type = filters.remove("status");
      if (type != null) filters.put("type", type);
    }
    if (resource.equals("repair-orders") || resource.equals("reports-repairs")) {
      if (priorityFilter.getValue() != null && !priorityFilter.getValue().isBlank())
        filters.put("priority", priorityFilter.getValue());
      if (manager()
          && technicianFilter.getValue() != null
          && !technicianFilter.getValue().id().isBlank())
        filters.put("technician_id", technicianFilter.getValue().id());
    }
    if (resource.startsWith("reports-") || resource.equals("dashboard")) {
      if (fromDate.getValue() != null) filters.put("from", fromDate.getValue().toString());
      if (toDate.getValue() != null) filters.put("to", toDate.getValue().toString());
    } else if (applyDates) {
      if (fromDate.getValue() != null)
        filters.put("from", fromDate.getValue().atStartOfDay(Forms.zone).toInstant().toString());
      if (toDate.getValue() != null)
        filters.put(
            "to", toDate.getValue().plusDays(1).atStartOfDay(Forms.zone).toInstant().toString());
    }
    return filters;
  }

  private void load() {
    String requested = resource;
    int version = ++generation;
    if (resource.equals("shop-settings")) {
      run(
          gateway.settings(),
          result -> {
            if (version != generation) return;
            workspace.setCenter(new ScrollPane(summary(result)));
            paging.setText("");
            previous.setDisable(true);
            next.setDisable(true);
          });
      return;
    }
    if (resource.equals("dashboard") || resource.startsWith("reports-")) {
      String type = resource.equals("dashboard") ? "dashboard" : resource.substring(8);
      run(
          gateway.report(type, filters()),
          result -> {
            if (version != generation) return;
            renderReport(result);
            paging.setText("");
            previous.setDisable(true);
            next.setDisable(true);
          });
      return;
    }
    run(
        gateway.list(resource, filters()),
        result -> {
          if (!resource.equals(requested) || version != generation) return;
          var rows = new ArrayList<JsonNode>();
          result.path("content").forEach(rows::add);
          table.setItems(FXCollections.observableArrayList(rows));
          long total = result.path("totalElements").asLong();
          paging.setText("Страница " + (state.page() + 1) + " · записей " + total);
          previous.setDisable(state.page() == 0);
          next.setDisable((state.page() + 1L) * 20 >= total);
        });
  }

  private void renderReport(JsonNode result) {
    var page = new VBox(20);
    page.setStyle("-fx-padding:24");
    var cards = new javafx.scene.layout.TilePane(16, 16);
    var tabs = new TabPane();
    result
        .fields()
        .forEachRemaining(
            entry -> {
              var node = entry.getValue();
              if (node.isValueNode()) {
                var heading = new Label(label(entry.getKey()));
                var number = new Label(node.isNull() ? "—" : Labels.value(result, entry.getKey()));
                number.setStyle("-fx-font-size:25px;-fx-font-weight:bold;-fx-text-fill:#2463eb");
                var card = new VBox(10, heading, number);
                card.getStyleClass().add("metric-card");
                cards.getChildren().add(card);
              } else if (node.isArray()) {
                var rows = arrayTable(node);
                rows.setPrefHeight(360);
                var tab = new Tab(label(entry.getKey()), rows);
                tab.setClosable(false);
                tabs.getTabs().add(tab);
              } else {
                var tab = new Tab(label(entry.getKey()), summary(node));
                tab.setClosable(false);
                tabs.getTabs().add(tab);
              }
            });
    if (resource.equals("dashboard")) {
      result
          .path("by_status")
          .forEach(
              status -> {
                if (!Set.of(
                        "RECEIVED",
                        "DIAGNOSIS",
                        "IN_REPAIR",
                        "WAITING_FOR_PARTS",
                        "READY_FOR_PICKUP")
                    .contains(status.path("status").asText())) return;
                var number = new Label(status.path("count").asText());
                number.setStyle("-fx-font-size:25px;-fx-font-weight:bold;-fx-text-fill:#2463eb");
                var card =
                    new VBox(10, new Label(Labels.text(status.path("status").asText())), number);
                card.getStyleClass().add("metric-card");
                cards.getChildren().add(card);
              });
      if (manager()) {
        var quick = new FlowPane(10, 10);
        var newOrder = new Button("Новый заказ");
        newOrder.getStyleClass().add("primary");
        newOrder.setOnAction(
            e -> {
              show("repair-orders", "Заказы");
              form(null);
            });
        var newCustomer = new Button("Новый клиент");
        newCustomer.setOnAction(
            e -> {
              show("customers", "Клиенты");
              form(null);
            });
        var receipt = new Button("Поступление деталей");
        receipt.setOnAction(
            e ->
                run(
                    gateway.lookups(true, owner()),
                    options ->
                        Forms.show(
                                "Поступление",
                                List.of(
                                    typed("part_id", "Деталь", "lookup:parts", true),
                                    f("quantity", "Количество"),
                                    f("unit_cost", "Стоимость единицы"),
                                    typed("reason", "Основание", "multiline", true)),
                                null,
                                options)
                            .ifPresent(
                                values ->
                                    run(
                                        gateway.command(
                                            "parts",
                                            values.get("part_id").toString(),
                                            "receipts",
                                            values),
                                        v -> load()))));
        quick.getChildren().addAll(newOrder, newCustomer, receipt);
        page.getChildren().add(quick);
      }
    }
    page.getChildren().addAll(cards, tabs);
    var scroll = new ScrollPane(page);
    scroll.setFitToWidth(true);
    workspace.setCenter(scroll);
  }

  private void editSettings() {
    run(
        gateway.settings(),
        settings ->
            Forms.show(
                    "Настройки мастерской",
                    List.of(
                        f("name", "Название"),
                        optional("phone", "Телефон"),
                        optional("address", "Адрес"),
                        f("timezone", "Часовой пояс"),
                        f("currency", "Валюта"),
                        f("warranty_days", "Гарантия, дней")),
                    settings,
                    Map.of())
                .ifPresent(
                    values ->
                        run(
                            gateway.saveSettings(values),
                            saved -> {
                              Forms.zone = java.time.ZoneId.of(saved.path("timezone").asText());
                              load();
                            })));
  }

  private void refreshUnread() {
    if (user == null) return;
    gateway
        .unread()
        .thenAccept(
            result ->
                Platform.runLater(
                    () -> {
                      if (notificationsButton != null && user != null)
                        notificationsButton.setText(
                            "Уведомления (" + result.path("unread").asLong() + ")");
                    }));
  }

  private void signedOut() {
    generation++;
    if (polling != null) polling.stop();
    gateway.clear();
    table.getItems().clear();
    for (var window : new ArrayList<>(javafx.stage.Window.getWindows()))
      if (window != root.getScene().getWindow()) window.hide();
    user = null;
    root.setLeft(null);
    workspace.setTop(null);
    workspace.setBottom(null);
    workspace.setCenter(null);
    root.setCenter(loginPane);
    loginError.setText("");
  }

  private void exportCsv() {
    var chooser = new javafx.stage.FileChooser();
    chooser.setTitle("Сохранить CSV");
    chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
    chooser.setInitialFileName("fixflow-" + resource + ".csv");
    var file = chooser.showSaveDialog(root.getScene().getWindow());
    if (file == null) return;
    if (resource.startsWith("reports-"))
      run(
          gateway
              .reportCsv(resource.substring(8), filters())
              .thenCompose(text -> ExportService.write(file.toPath(), text)),
          saved -> message.setText("Сохранён отчёт: " + saved));
    else if (resource.equals("dashboard") || resource.equals("shop-settings")) {
      message.setText("Для экспорта выберите таблицу или отчёт");
    } else {
      var keys = table.getColumns().stream().map(c -> c.getUserData().toString()).toList();
      String csv = ExportService.csv(List.copyOf(table.getItems()), keys);
      run(
          ExportService.write(file.toPath(), csv),
          saved -> message.setText("Сохранена текущая страница: " + saved));
    }
  }

  private void form(JsonNode initial) {
    run(
        gateway.lookups(manager(), owner()),
        options -> {
          var fields = fields(resource, initial != null);
          Forms.show(initial == null ? "Новая запись" : "Редактирование", fields, initial, options)
              .ifPresent(
                  values ->
                      run(
                          gateway.save(
                              resource,
                              initial == null ? null : initial.path("id").asText(),
                              values),
                          saved -> load()));
        });
  }

  private List<Forms.Field> fields(String name, boolean editing) {
    return switch (name) {
      case "customers" ->
          List.of(
              f("full_name", "ФИО"),
              f("phone", "Телефон"),
              optional("email", "Email"),
              typed("comment", "Комментарий", "multiline", false),
              typed("active", "Активен", "boolean", false));
      case "devices" ->
          List.of(
              typed("customer_id", "Клиент", "lookup:customers", true),
              typed(
                  "type",
                  "Тип",
                  "choices:LAPTOP,DESKTOP,SMARTPHONE,TABLET,MONITOR,PRINTER,CONSOLE,OTHER",
                  true),
              f("manufacturer", "Производитель"),
              f("model", "Модель"),
              optional("serial_number", "Серийный номер"),
              optional("imei", "IMEI"),
              typed("comment", "Комментарий", "multiline", false));
      case "users" ->
          List.of(
              f("full_name", "ФИО"),
              f("email", "Email"),
              typed("password", "Пароль", "password", !editing),
              typed("role", "Роль", "choices:TECHNICIAN,MANAGER,OWNER", true),
              typed("active", "Активен", "boolean", false));
      case "parts" ->
          List.of(
              f("name", "Наименование"),
              f("sku", "Артикул SKU"),
              typed(
                  "category",
                  "Категория",
                  "choices:STORAGE,MEMORY,BATTERY,DISPLAY,KEYBOARD,MOTHERBOARD,CABLE,COOLING,CONNECTOR,TOOLS,OTHER",
                  true),
              optional("manufacturer", "Производитель"),
              typed("supplier_id", "Поставщик", "lookup:suppliers", false),
              f("purchase_price", "Закупочная цена"),
              f("sale_price", "Цена для клиента"),
              f("minimum_quantity", "Минимальный остаток"),
              optional("storage_location", "Место хранения"),
              typed("active", "Активна", "boolean", false));
      case "suppliers" ->
          List.of(
              f("name", "Компания"),
              optional("contact_name", "Контактное лицо"),
              optional("phone", "Телефон"),
              optional("email", "Email"),
              optional("address", "Адрес"),
              typed("comment", "Комментарий", "multiline", false),
              typed("active", "Активен", "boolean", false));
      case "purchase-orders" ->
          List.of(
              typed("supplier_id", "Поставщик", "lookup:suppliers", true),
              typed("expected_at", "Ожидаемая дата", "instant", false),
              typed("comment", "Комментарий", "multiline", false));
      case "expenses" ->
          List.of(
              f("amount", "Сумма"),
              typed(
                  "category",
                  "Категория",
                  "choices:PARTS_PURCHASE,RENT,UTILITIES,TOOLS,MAINTENANCE,OTHER",
                  true),
              typed("description", "Описание", "multiline", true),
              typed("order_id", "Связанный ремонт", "lookup:repair-orders", false),
              typed("purchase_order_id", "Связанная закупка", "lookup:purchase-orders", false));
      case "notifications" ->
          List.of(
              typed("recipient_id", "Получатель", "lookup:notification-recipients", true),
              typed("order_id", "Связанный заказ", "lookup:repair-orders", false),
              typed("message", "Сообщение", "multiline", true));
      case "repair-orders" ->
          editing
              ? List.of(
                  typed("problem", "Неисправность", "multiline", true),
                  typed("priority", "Приоритет", "choices:LOW,NORMAL,HIGH,URGENT", true),
                  f("estimated_total", "Согласуемая стоимость"),
                  typed("due_at", "Плановая дата", "instant", false),
                  typed("comment", "Комментарий", "multiline", false))
              : List.of(
                  typed("customer_id", "Клиент", "lookup:customers", true),
                  typed("device_id", "Устройство", "lookup:devices", true),
                  typed("technician_id", "Мастер", "lookup:technicians", false),
                  typed("problem", "Неисправность", "multiline", true),
                  typed("intake_condition", "Состояние при приёме", "multiline", false),
                  optional("accessories", "Комплектность"),
                  typed("priority", "Приоритет", "choices:NORMAL,LOW,HIGH,URGENT", true),
                  f("estimated_total", "Предварительная стоимость"),
                  typed("due_at", "Плановая дата", "instant", false));
      default -> List.of();
    };
  }

  private void openSelected() {
    var selected = table.getSelectionModel().getSelectedItem();
    if (selected == null) {
      message.setText("Выберите запись");
      return;
    }
    String selectedResource = resource;
    if (Set.of(
            "repair-orders",
            "purchase-orders",
            "customers",
            "devices",
            "parts",
            "suppliers",
            "users",
            "warranties")
        .contains(resource))
      run(
          gateway.details(resource, selected.path("id").asText()),
          row -> {
            switch (selectedResource) {
              case "repair-orders" -> orderDialog(row);
              case "purchase-orders" -> purchaseDialog(row);
              case "parts" -> partDialog(row);
              case "warranties" -> warrantyDialog(row);
              default -> showDetails(row);
            }
          });
    else if (resource.equals("payments")) paymentDialog(selected);
    else if (resource.equals("notifications")) notificationDialog(selected);
    else showDetails(selected);
  }

  private VBox summary(JsonNode row) {
    var box = new VBox(10);
    row.fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey().equals("version")
                  || entry.getKey().equals("id")
                  || entry.getKey().endsWith("_id")
                  || entry.getValue().isContainerNode()
                  || entry.getValue().isNull()) return;
              var line =
                  new Label(label(entry.getKey()) + ": " + Labels.value(row, entry.getKey()));
              line.setWrapText(true);
              box.getChildren().add(line);
            });
    return box;
  }

  private void showDetails(JsonNode row) {
    var dialog = new Dialog<>();
    dialog.setTitle("Карточка");
    var box = summary(row);
    row.fields()
        .forEachRemaining(
            entry -> {
              if (entry.getValue().isArray()) {
                box.getChildren()
                    .addAll(new Label(label(entry.getKey())), arrayTable(entry.getValue()));
              }
            });
    if (resource.equals("customers") || resource.equals("devices")) {
      String type = resource;
      var history = new Button("История ремонтов");
      history.setOnAction(
          e ->
              run(
                  gateway.list(
                      "repair-orders",
                      Map.of(
                          type.equals("customers") ? "customer_id" : "device_id",
                          row.path("id").asText(),
                          "size",
                          "100")),
                  result -> {
                    var historyDialog = new Dialog<>();
                    historyDialog.setTitle("История ремонтов");
                    historyDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    historyDialog.getDialogPane().setContent(arrayTable(result.path("content")));
                    historyDialog.getDialogPane().setPrefSize(1100, 650);
                    historyDialog.showAndWait();
                  }));
      box.getChildren().add(history);
    }
    dialog.getDialogPane().setContent(new ScrollPane(box));
    dialog.getDialogPane().setPrefSize(760, 560);
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialog.showAndWait();
  }

  private void orderDialog(JsonNode order) {
    var dialog = new Dialog<>();
    dialog.setTitle(order.path("order_number").asText());
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialog.getDialogPane().setPrefSize(1000, 700);
    var content = new VBox(14);
    var summary =
        new Label(
            order.path("device").path("model").asText()
                + " · "
                + order.path("status").asText()
                + "\n"
                + order.path("problem").asText()
                + "\nСтоимость: "
                + order.path("estimated_total").asText());
    summary.setWrapText(true);
    content.getChildren().add(summary);
    var buttons = new FlowPane(10, 10);
    var select = new ComboBox<String>();
    select.setId("order-status");
    order.path("allowed_statuses").forEach(s -> select.getItems().add(s.asText()));
    var change = new Button("Изменить статус");
    change.setId("order-status-apply");
    change.setOnAction(
        e -> {
          if (select.getValue() != null)
            run(
                gateway.command(
                    "repair-orders",
                    order.path("id").asText(),
                    "status",
                    Map.of("status", select.getValue(), "version", order.path("version").asLong())),
                v -> {
                  dialog.close();
                  load();
                });
        });
    buttons.getChildren().addAll(select, change);
    orderAction(
        buttons,
        dialog,
        order,
        "Диагностика",
        "diagnostics",
        List.of(
            typed("description", "Неисправность", "multiline", true),
            typed("result", "Результат", "multiline", false),
            typed("recommendation", "Рекомендация", "multiline", false),
            optional("estimated_cost", "Оценка стоимости"),
            typed("comment", "Комментарий диагностики", "multiline", false)));
    var workFields =
        new ArrayList<>(
            List.of(
                f("name", "Выполненная работа"),
                f("quantity", "Количество"),
                typed("description", "Описание", "multiline", false)));
    if (manager()) workFields.add(f("unit_price", "Цена за единицу"));
    orderAction(buttons, dialog, order, "Добавить работу", "works", workFields);
    if (manager())
      orderAction(
          buttons,
          dialog,
          order,
          "Согласование",
          "approvals",
          List.of(
              f("amount", "Согласованная сумма"),
              typed("comment", "Как получено согласие клиента", "multiline", true)));
    String orderId = order.path("id").asText();
    String status = order.path("status").asText();
    if (manager())
      orderAction(
          buttons,
          dialog,
          order,
          "Назначить мастера",
          "assign",
          List.of(typed("technician_id", "Мастер", "lookup:technicians", true)));
    if (status.equals("IN_REPAIR"))
      orderAction(
          buttons,
          dialog,
          order,
          "Использовать деталь",
          "parts",
          List.of(typed("part_id", "Деталь", "lookup:parts", true), f("quantity", "Количество")));
    if (status.equals("TESTING"))
      commandButton(
          buttons,
          dialog,
          "Завершить ремонт",
          () ->
              gateway.command(
                  "repair-orders",
                  orderId,
                  "complete",
                  Map.of("version", order.path("version").asLong())));
    if (manager() && order.hasNonNull("final_total") && !status.equals("CANCELLED"))
      orderAction(
          buttons,
          dialog,
          order,
          "Принять оплату",
          "payments",
          List.of(
              f("amount", "Сумма"),
              typed("method", "Способ оплаты", "choices:CASH,CARD,BANK_TRANSFER,OTHER", true),
              optional("comment", "Комментарий")));
    if (manager() && status.equals("READY_FOR_PICKUP"))
      commandButton(
          buttons,
          dialog,
          "Выдать устройство",
          () ->
              gateway.command(
                  "repair-orders",
                  orderId,
                  "deliver",
                  Map.of("version", order.path("version").asLong())));
    if (manager()
        && !Set.of("COMPLETED", "READY_FOR_PICKUP", "DELIVERED", "CANCELLED").contains(status)) {
      var cancelFields =
          new ArrayList<>(List.of(typed("reason", "Причина отмены", "multiline", true)));
      if (owner())
        cancelFields.add(
            typed(
                "keep_used_parts",
                "Подтверждаю невозвратность использованных деталей",
                "boolean",
                false));
      orderAction(buttons, dialog, order, "Отменить заказ", "cancel", cancelFields);
    }
    if (owner() && order.hasNonNull("final_total"))
      orderAction(
          buttons,
          dialog,
          order,
          "Корректировка цены",
          "adjustments",
          List.of(
              f("amount", "Изменение суммы (может быть отрицательным)"),
              typed("reason", "Основание", "multiline", true)));
    if (manager()) {
      var intake = new Button("Печать приёмной квитанции");
      intake.setOnAction(e -> printDocument(orderId, "intake", null));
      buttons.getChildren().add(intake);
      if (order.hasNonNull("completed_at")) {
        var completion = new Button("Печать акта");
        completion.setOnAction(e -> printDocument(orderId, "completion", null));
        buttons.getChildren().add(completion);
      }
      var tracking = new Button("Ссылка для клиента");
      tracking.setOnAction(
          e ->
              run(
                  gateway.command("repair-orders", orderId, "tracking", Map.of()),
                  issued -> {
                    var field = new TextField(gateway.publicUrl(issued));
                    field.setEditable(false);
                    var linkDialog = new Dialog<>();
                    linkDialog.setTitle("Проверка статуса · JSON API");
                    linkDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    var copy = new Button("Копировать ссылку");
                    copy.setOnAction(
                        event -> {
                          var clipboard = new javafx.scene.input.ClipboardContent();
                          clipboard.putString(field.getText());
                          javafx.scene.input.Clipboard.getSystemClipboard().setContent(clipboard);
                        });
                    linkDialog
                        .getDialogPane()
                        .setContent(
                            new VBox(
                                12,
                                new Label(
                                    "Новая ссылка заменяет предыдущую. Не передавайте её"
                                        + " посторонним."),
                                field,
                                copy));
                    linkDialog.getDialogPane().setPrefWidth(750);
                    linkDialog.showAndWait();
                  }));
      buttons.getChildren().add(tracking);
      commandButton(buttons, dialog, "Отозвать ссылки", () -> gateway.revokeTracking(orderId));
    }
    if (order.has("balance"))
      content
          .getChildren()
          .add(
              new Label(
                  "Начислено: "
                      + order.path("balance").path("charged").asText()
                      + " · Оплачено: "
                      + order.path("balance").path("paid").asText()
                      + " · Долг: "
                      + order.path("balance").path("debt").asText()
                      + " · К возврату: "
                      + order.path("balance").path("credit").asText()));
    content.getChildren().add(buttons);
    var tabs = new TabPane();
    var sections =
        new ArrayList<>(
            List.of("history", "diagnostics", "works", "approvals", "parts", "warranties"));
    if (manager()) sections.addAll(List.of("payments", "refunds", "adjustments"));
    for (String section : sections) {
      var rows = arrayTable(order.path(section));
      rows.setId("section-" + section);
      var pane = new VBox(8, rows);
      VBox.setVgrow(rows, Priority.ALWAYS);
      if (section.equals("works")
          && manager()
          && !Set.of("COMPLETED", "READY_FOR_PICKUP", "DELIVERED", "CANCELLED").contains(status)) {
        var price = new Button("Установить цену выбранной работы");
        price.setId("price-work");
        price.setOnAction(
            e -> {
              var row = rows.getSelectionModel().getSelectedItem();
              if (row != null)
                Forms.show(
                        "Цена работы", List.of(f("unit_price", "Цена за единицу")), row, Map.of())
                    .ifPresent(
                        values ->
                            run(
                                gateway.updateWorkPrice(orderId, row.path("id").asText(), values),
                                v -> {
                                  dialog.close();
                                  load();
                                }));
            });
        pane.getChildren().add(price);
      }
      if (section.equals("parts")
          && manager()
          && !Set.of("COMPLETED", "READY_FOR_PICKUP", "DELIVERED", "CANCELLED").contains(status)) {
        var back = new Button("Вернуть выбранную деталь на склад");
        back.setOnAction(
            e -> {
              var row = rows.getSelectionModel().getSelectedItem();
              if (row != null)
                Forms.show(
                        "Возврат детали",
                        List.of(
                            f("quantity", "Количество"),
                            typed("reason", "Причина", "multiline", true)),
                        null,
                        Map.of())
                    .ifPresent(
                        values ->
                            run(
                                gateway.command(
                                    "repair-orders",
                                    orderId,
                                    "parts/" + row.path("id").asText() + "/return",
                                    values),
                                v -> {
                                  dialog.close();
                                  load();
                                }));
            });
        pane.getChildren().add(back);
      }
      var tab = new Tab(label(section), pane);
      tab.setClosable(false);
      tabs.getTabs().add(tab);
    }
    VBox.setVgrow(tabs, Priority.ALWAYS);
    content.getChildren().add(tabs);
    dialog.getDialogPane().setContent(content);
    dialog.showAndWait();
  }

  private void orderAction(
      FlowPane buttons,
      Dialog<?> parent,
      JsonNode order,
      String label,
      String command,
      List<Forms.Field> fields) {
    var button = new Button(label);
    button.setId("order-" + command);
    button.setOnAction(
        e ->
            run(
                gateway.lookups(manager(), owner()),
                options ->
                    Forms.show(label, fields, null, options)
                        .ifPresent(
                            values -> {
                              values
                                  .entrySet()
                                  .removeIf(
                                      entry -> entry.getValue() instanceof String s && s.isBlank());
                              values.put("version", order.path("version").asLong());
                              run(
                                  gateway.command(
                                      "repair-orders", order.path("id").asText(), command, values),
                                  v -> {
                                    parent.close();
                                    load();
                                  });
                            })));
    buttons.getChildren().add(button);
  }

  private void commandButton(
      FlowPane buttons,
      Dialog<?> parent,
      String label,
      java.util.function.Supplier<CompletableFuture<JsonNode>> command) {
    var button = new Button(label);
    button.setOnAction(
        e -> {
          if (new Alert(Alert.AlertType.CONFIRMATION, label + "?", ButtonType.OK, ButtonType.CANCEL)
                  .showAndWait()
                  .orElse(ButtonType.CANCEL)
              == ButtonType.OK)
            run(
                command.get(),
                v -> {
                  parent.close();
                  load();
                });
        });
    buttons.getChildren().add(button);
  }

  private void partDialog(JsonNode part) {
    var dialog = new Dialog<>();
    dialog.setTitle(part.path("name").asText());
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var box = summary(part);
    var buttons = new FlowPane(10, 10);
    if (manager())
      genericAction(
          buttons,
          dialog,
          "Поступление",
          "parts",
          part.path("id").asText(),
          "receipts",
          List.of(
              f("quantity", "Количество"),
              f("unit_cost", "Закупочная цена за единицу"),
              typed("reason", "Основание", "multiline", true)),
          Map.of());
    if (owner())
      genericAction(
          buttons,
          dialog,
          "Инвентаризация / корректировка",
          "parts",
          part.path("id").asText(),
          "adjustments",
          List.of(
              typed("direction", "Направление", "choices:IN,OUT", true),
              f("quantity", "Количество"),
              f("unit_cost", "Стоимость единицы для прихода"),
              typed("reason", "Основание", "multiline", true)),
          Map.of());
    box.getChildren().add(buttons);
    dialog.getDialogPane().setContent(new ScrollPane(box));
    dialog.getDialogPane().setPrefSize(760, 600);
    dialog.showAndWait();
  }

  private void genericAction(
      FlowPane buttons,
      Dialog<?> parent,
      String label,
      String name,
      String id,
      String action,
      List<Forms.Field> fields,
      Map<String, List<JsonNode>> options) {
    var button = new Button(label);
    button.setId("action-" + action);
    button.setOnAction(
        e ->
            Forms.show(label, fields, null, options)
                .ifPresent(
                    values ->
                        run(
                            gateway.command(name, id, action, values),
                            v -> {
                              parent.close();
                              load();
                            })));
    buttons.getChildren().add(button);
  }

  private void paymentDialog(JsonNode payment) {
    var dialog = new Dialog<>();
    dialog.setTitle("Оплата и возврат");
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var box = summary(payment);
    var buttons = new FlowPane();
    if (owner())
      genericAction(
          buttons,
          dialog,
          "Возврат средств",
          "payments",
          payment.path("id").asText(),
          "refunds",
          List.of(f("amount", "Сумма возврата"), typed("reason", "Основание", "multiline", true)),
          Map.of());
    var print = new Button("Печать квитанции");
    print.setOnAction(
        e ->
            printDocument(
                payment.path("order_id").asText(), "payment", payment.path("id").asText()));
    buttons.getChildren().add(print);
    box.getChildren().add(buttons);
    dialog.getDialogPane().setContent(box);
    dialog.showAndWait();
  }

  private void notificationDialog(JsonNode notification) {
    var dialog = new Dialog<>();
    dialog.setTitle("Уведомление");
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var box = summary(notification);
    var read = new Button("Отметить прочитанным");
    read.setOnAction(
        e ->
            run(
                gateway.markRead(notification.path("id").asText()),
                v -> {
                  dialog.close();
                  refreshUnread();
                  load();
                }));
    box.getChildren().add(read);
    if (notification.hasNonNull("order_id")) {
      var open = new Button("Открыть заказ");
      open.setOnAction(
          e ->
              run(
                  gateway.details("repair-orders", notification.path("order_id").asText()),
                  this::orderDialog));
      box.getChildren().add(open);
    }
    dialog.getDialogPane().setContent(box);
    dialog.showAndWait();
  }

  private void printDocument(String order, String kind, String payment) {
    run(
        gateway.document(order, kind, payment),
        document -> {
          var job = javafx.print.PrinterJob.createPrinterJob();
          if (job == null) {
            new Alert(
                    Alert.AlertType.INFORMATION,
                    "Принтер не найден. Установите принтер или системный PDF-принтер.",
                    ButtonType.OK)
                .show();
            return;
          }
          if (!job.showPrintDialog(root.getScene().getWindow())) return;
          var page = new VBox(6);
          for (String line : document.path("content").asText().split("\n")) {
            var label = new Label(line);
            label.setWrapText(true);
            label.setMaxWidth(job.getJobSettings().getPageLayout().getPrintableWidth());
            page.getChildren().add(label);
          }
          if (job.printPage(page)) job.endJob();
        });
  }

  private void warrantyDialog(JsonNode warranty) {
    var dialog = new Dialog<>();
    dialog.setTitle(warranty.path("number").asText());
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var box = summary(warranty);
    box.getChildren().add(arrayTable(warranty.path("claims")));
    var buttons = new FlowPane();
    genericAction(
        buttons,
        dialog,
        "Гарантийное обращение",
        "warranties",
        warranty.path("id").asText(),
        "claims",
        List.of(typed("description", "Неисправность", "multiline", true)),
        Map.of());
    box.getChildren().add(buttons);
    dialog.getDialogPane().setContent(box);
    dialog.getDialogPane().setPrefSize(850, 600);
    dialog.showAndWait();
  }

  private void purchaseDialog(JsonNode purchase) {
    var dialog = new Dialog<>();
    dialog.setTitle(purchase.path("number").asText());
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    dialog.getDialogPane().setPrefSize(1000, 650);
    var rows = arrayTable(purchase.path("items"));
    var buttons = new FlowPane(10, 10);
    String id = purchase.path("id").asText(), status = purchase.path("status").asText();
    if (status.equals("DRAFT")) {
      var add = new Button("Добавить позицию");
      add.setOnAction(
          e ->
              run(
                  gateway.lookups(true, true),
                  options ->
                      Forms.show(
                              "Позиция закупки",
                              List.of(
                                  typed("part_id", "Деталь", "lookup:parts", true),
                                  f("quantity", "Количество"),
                                  f("unit_price", "Закупочная цена")),
                              null,
                              options)
                          .ifPresent(
                              values ->
                                  run(
                                      gateway.command("purchase-orders", id, "items", values),
                                      v -> {
                                        dialog.close();
                                        load();
                                      }))));
      buttons.getChildren().add(add);
      var change = new Button("Изменить позицию");
      change.setOnAction(
          e -> {
            var selected = rows.getSelectionModel().getSelectedItem();
            if (selected != null)
              run(
                  gateway.lookups(true, true),
                  options ->
                      Forms.show(
                              "Изменить позицию",
                              List.of(
                                  typed("part_id", "Деталь", "lookup:parts", true),
                                  f("quantity", "Количество"),
                                  f("unit_price", "Закупочная цена")),
                              selected,
                              options)
                          .ifPresent(
                              values ->
                                  run(
                                      gateway.updatePurchaseItem(
                                          id, selected.path("id").asText(), values),
                                      v -> {
                                        dialog.close();
                                        load();
                                      })));
          });
      buttons.getChildren().add(change);
      commandButton(
          buttons,
          dialog,
          "Заказать",
          () -> gateway.command("purchase-orders", id, "order", Map.of()));
    }
    if (Set.of("ORDERED", "PARTIALLY_RECEIVED").contains(status)) {
      var receive = new Button("Принять выбранную позицию");
      receive.setOnAction(
          e -> {
            var row = rows.getSelectionModel().getSelectedItem();
            if (row == null) return;
            Forms.show(
                    "Поступление",
                    List.of(f("quantity", "Количество к поступлению")),
                    null,
                    Map.of())
                .ifPresent(
                    values ->
                        run(
                            gateway.command(
                                "purchase-orders",
                                id,
                                "receive",
                                Map.of(
                                    "items",
                                    List.of(
                                        Map.of(
                                            "item_id",
                                            row.path("id").asText(),
                                            "quantity",
                                            values.get("quantity"))))),
                            v -> {
                              dialog.close();
                              load();
                            }));
          });
      buttons.getChildren().add(receive);
    }
    if (Set.of("DRAFT", "ORDERED").contains(status))
      commandButton(
          buttons,
          dialog,
          "Отменить закупку",
          () -> gateway.command("purchase-orders", id, "cancel", Map.of()));
    var box =
        new VBox(
            12,
            new Label("Статус: " + status + " · Сумма: " + purchase.path("total").asText()),
            buttons,
            rows);
    VBox.setVgrow(rows, Priority.ALWAYS);
    dialog.getDialogPane().setContent(box);
    dialog.showAndWait();
  }

  private TableView<JsonNode> arrayTable(JsonNode array) {
    var view = new TableView<JsonNode>();
    var rows = new ArrayList<JsonNode>();
    array.forEach(rows::add);
    if (!rows.isEmpty())
      rows.getFirst()
          .fieldNames()
          .forEachRemaining(
              key -> {
                if (key.equals("id") || key.endsWith("_id")) return;
                var col = new TableColumn<JsonNode, String>(label(key));
                col.setCellValueFactory(
                    d -> new SimpleStringProperty(Labels.value(d.getValue(), key)));
                view.getColumns().add(col);
              });
    view.setItems(FXCollections.observableArrayList(rows));
    view.setPlaceholder(new Label("Записей нет"));
    return view;
  }

  private <T> void run(CompletableFuture<T> task, Consumer<T> success) {
    var session = user;
    requests++;
    progress.setVisible(true);
    message.setText("Загрузка…");
    task.whenComplete(
        (result, error) ->
            Platform.runLater(
                () -> {
                  requests--;
                  progress.setVisible(requests > 0);
                  if (session != user) return;
                  if (error != null) {
                    Throwable cause = error;
                    while (cause instanceof CompletionException && cause.getCause() != null)
                      cause = cause.getCause();
                    if (cause instanceof ApiClient.Failure failure && failure.status() == 401)
                      signedOut();
                    message.setText(errorMessage(error));
                    var alert =
                        new Alert(Alert.AlertType.ERROR, errorMessage(error), ButtonType.OK);
                    alert.setHeaderText("Операция не выполнена");
                    alert.show();
                    return;
                  }
                  message.setText("");
                  success.accept(result);
                }));
  }

  private String errorMessage(Throwable error) {
    while ((error instanceof CompletionException || error instanceof ExecutionException)
        && error.getCause() != null) error = error.getCause();
    if (error instanceof ApiClient.Failure f && !f.fields().isEmpty())
      return error.getMessage()
          + "\n"
          + String.join(
              "\n",
              f.fields().entrySet().stream()
                  .map(e -> Labels.text(e.getKey()) + ": " + e.getValue())
                  .toList());
    return error.getMessage() == null ? "Не удалось выполнить запрос" : error.getMessage();
  }

  private String label(String key) {
    return Labels.text(key);
  }

  public void stop() {
    if (polling != null) polling.stop();
    if (gateway != null) gateway.stop();
  }
}
