package com.fixflow.desktop.component;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

public final class Forms {
  public static java.time.ZoneId zone = java.time.ZoneId.of("Asia/Almaty");
  private static final Set<String> NUMBERS =
      Set.of(
          "amount",
          "quantity",
          "unit_cost",
          "unit_price",
          "estimated_total",
          "sale_price",
          "purchase_price",
          "minimum_quantity",
          "estimated_cost");

  public record Field(String key, String label, String kind, boolean required) {}

  public record Choice(String id, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  private Forms() {}

  public static Optional<Map<String, Object>> show(
      String title, List<Field> fields, JsonNode initial, Map<String, List<JsonNode>> options) {
    var dialog = new Dialog<Map<String, Object>>();
    dialog.setTitle(title);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    var grid = new GridPane();
    grid.setHgap(16);
    grid.setVgap(12);
    var controls = new LinkedHashMap<Field, Node>();
    int index = 0;
    for (var field : fields) {
      Node control;
      String value = initial == null ? "" : initial.path(field.key()).asText("");
      if (field.kind().startsWith("choices:")) {
        var combo = new ComboBox<String>();
        combo.getItems().addAll(field.kind().substring(8).split(","));
        if (!value.isBlank()) combo.setValue(value);
        else if (field.required()) combo.getSelectionModel().selectFirst();
        control = combo;
      } else if (field.kind().startsWith("lookup:")) {
        String resource = field.kind().substring(7);
        var combo = new ComboBox<Choice>();
        for (var row : options.getOrDefault(resource, List.of())) {
          String label =
              row.path("full_name").asText(row.path("model").asText(row.path("name").asText("")));
          if (resource.equals("devices"))
            label =
                row.path("manufacturer").asText()
                    + " "
                    + label
                    + " · "
                    + row.path("serial_number").asText();
          if (resource.equals("repair-orders"))
            label = row.path("order_number").asText() + " · " + row.path("customer_name").asText();
          if (resource.equals("purchase-orders")) label = row.path("number").asText();
          var choice = new Choice(row.path("id").asText(), label);
          combo.getItems().add(choice);
          if (choice.id().equals(value)) combo.setValue(choice);
        }
        control = combo;
      } else if (field.kind().equals("boolean")) {
        var check = new CheckBox();
        check.setSelected(
            value.isBlank() ? field.key().equals("active") : Boolean.parseBoolean(value));
        control = check;
      } else if (field.kind().equals("instant")) {
        var date = new DatePicker();
        if (!value.isBlank())
          date.setValue(java.time.Instant.parse(value).atZone(zone).toLocalDate());
        control = date;
      } else if (field.kind().equals("multiline")) {
        var text = new TextArea(value);
        text.setPrefRowCount(3);
        text.setWrapText(true);
        control = text;
      } else if (field.kind().equals("password")) {
        var text = new PasswordField();
        text.setPromptText("Не менее 10 символов");
        control = text;
      } else {
        var text = new TextField(value);
        control = text;
      }
      control.setId(field.key());
      if (control instanceof javafx.scene.layout.Region region) region.setPrefWidth(360);
      grid.add(new Label(field.label() + (field.required() ? " *" : "")), 0, index);
      grid.add(control, 1, index++);
      controls.put(field, control);
    }
    var error = new Label();
    error.setStyle("-fx-text-fill:#b42318");
    var scroll = new ScrollPane(grid);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(Math.min(600, fields.size() * 54 + 10));
    dialog.getDialogPane().setContent(new VBox(12, scroll, error));
    dialog
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            event -> {
              for (var entry : controls.entrySet()) {
                Object value = value(entry.getValue());
                if (entry.getKey().required() && (value == null || value.toString().isBlank())) {
                  error.setText("Заполните поле: " + entry.getKey().label());
                  event.consume();
                  return;
                }
              }
            });
    dialog.setResultConverter(
        button -> {
          if (button != ButtonType.OK) return null;
          var result = new LinkedHashMap<String, Object>();
          controls.forEach(
              (f, c) -> {
                Object value = value(c);
                if (value != null && !value.toString().isBlank()) result.put(f.key(), value);
                else if (!f.kind().startsWith("lookup:")) result.put(f.key(), "");
              });
          if (initial != null && initial.has("version"))
            result.put("version", initial.path("version").asLong());
          return result;
        });
    return dialog.showAndWait();
  }

  private static Object value(Node node) {
    if (node instanceof DatePicker d)
      return d.getValue() == null
          ? null
          : d.getValue().atTime(18, 0).atZone(zone).toInstant().toString();
    if (node instanceof PasswordField p) return p.getText();
    if (node instanceof TextInputControl t) {
      String text = t.getText().trim();
      return NUMBERS.contains(node.getId())
          ? text.replace(" ", "").replace("\u00a0", "").replace("\u202f", "").replace(',', '.')
          : text;
    }
    if (node instanceof CheckBox c) return c.isSelected();
    if (node instanceof ComboBox<?> c) {
      Object value = c.getValue();
      return value instanceof Choice choice ? choice.id() : value;
    }
    return null;
  }

  public static Field f(String key, String label) {
    return new Field(key, label, "text", true);
  }

  public static Field optional(String key, String label) {
    return new Field(key, label, "text", false);
  }

  public static Field typed(String key, String label, String kind, boolean required) {
    return new Field(key, label, kind, required);
  }
}
