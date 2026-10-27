package com.fixflow.desktop.component;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Labels {
  private Labels() {}

  public static String currency = "KZT";
  private static final Set<String> MONEY =
      Set.of(
          "estimated_total",
          "final_total",
          "unit_price",
          "unit_sale_price",
          "unit_cost",
          "total_cost",
          "amount",
          "sale_price",
          "purchase_price",
          "stock_value",
          "cash_received",
          "expenses",
          "debt",
          "total_debt",
          "cash_expenses",
          "cash_flow",
          "revenue",
          "parts_cost",
          "gross_profit",
          "operating_result",
          "average_check",
          "total_paid",
          "paid",
          "charged",
          "credit",
          "total",
          "refunded");
  private static final Map<String, String> TEXT = new HashMap<>();

  static {
    String[][] pairs = {
      {"order_number", "Заказ"},
      {"full_name", "ФИО"},
      {"customer_name", "Клиент"},
      {"device_model", "Устройство"},
      {"technician_name", "Мастер"},
      {"technician", "Мастер"},
      {"status", "Статус"},
      {"priority", "Приоритет"},
      {"due_at", "Плановая дата"},
      {"estimated_total", "Оценка"},
      {"final_total", "Итого"},
      {"phone", "Телефон"},
      {"active", "Активен"},
      {"type", "Тип"},
      {"model", "Модель"},
      {"manufacturer", "Производитель"},
      {"serial_number", "Серийный номер"},
      {"role", "Роль"},
      {"history", "История"},
      {"diagnostics", "Диагностика"},
      {"works", "Работы"},
      {"approvals", "Согласования"},
      {"parts", "Детали"},
      {"warranties", "Гарантии"},
      {"payments", "Платежи"},
      {"refunds", "Возвраты"},
      {"adjustments", "Корректировки"},
      {"quantity", "Количество"},
      {"returned_quantity", "Возвращено"},
      {"unit_price", "Цена"},
      {"unit_sale_price", "Цена для клиента"},
      {"unit_cost", "Себестоимость единицы"},
      {"total_cost", "Себестоимость"},
      {"name", "Название"},
      {"description", "Описание"},
      {"created_at", "Создано"},
      {"updated_at", "Изменено"},
      {"occurred_at", "Время"},
      {"from_status", "Было"},
      {"to_status", "Стало"},
      {"actor_name", "Сотрудник"},
      {"reason", "Основание"},
      {"result", "Результат"},
      {"recommendation", "Рекомендации"},
      {"estimated_cost", "Оценка стоимости"},
      {"comment", "Комментарий"},
      {"amount", "Сумма"},
      {"method", "Способ оплаты"},
      {"category", "Категория"},
      {"minimum_quantity", "Минимум"},
      {"sale_price", "Цена для клиента"},
      {"purchase_price", "Закупочная цена"},
      {"stock_value", "Стоимость остатков"},
      {"storage_location", "Место хранения"},
      {"contact_name", "Контактное лицо"},
      {"address", "Адрес"},
      {"number", "Номер"},
      {"expected_at", "Ожидается"},
      {"starts_on", "Начало"},
      {"ends_on", "Окончание"},
      {"terms", "Условия"},
      {"direction", "Направление"},
      {"after_quantity", "Остаток после"},
      {"part_name", "Деталь"},
      {"message", "Сообщение"},
      {"read_at", "Прочитано"},
      {"action", "Действие"},
      {"entity_type", "Объект"},
      {"total_orders", "Всего заказов"},
      {"overdue", "Просрочено"},
      {"delivered", "Выдано за период"},
      {"low_stock", "Дефицитные позиции"},
      {"cash_received", "Поступления за период"},
      {"expenses", "Расходы за период"},
      {"debt", "Задолженность"},
      {"total_debt", "Общая задолженность"},
      {"cash_expenses", "Денежные расходы"},
      {"cash_flow", "Денежный поток"},
      {"revenue", "Выручка"},
      {"parts_cost", "Себестоимость деталей"},
      {"gross_profit", "Валовая прибыль"},
      {"operating_result", "Операционный результат"},
      {"average_check", "Средний чек"},
      {"delivered_orders", "Выданные заказы"},
      {"by_status", "По статусам"},
      {"recent_orders", "Последние заказы"},
      {"overdue_orders", "Просроченные заказы"},
      {"upcoming_orders", "Ближайшие сроки"},
      {"recent_actions", "Последние действия"},
      {"by_technician", "По мастерам"},
      {"device_types", "Типы устройств"},
      {"completion", "Завершение ремонта"},
      {"usage", "Расход деталей"},
      {"debt_rows", "Задолженности"},
      {"expenses_by_category", "Расходы по категориям"},
      {"work_revenue", "Выручка по работам"},
      {"monthly", "Финансы по месяцам"},
      {"rows", "Данные"},
      {"count", "Количество"},
      {"orders", "Заказы"},
      {"completed", "Завершено"},
      {"average_hours", "Среднее время, ч"},
      {"month", "Месяц"},
      {"used", "Использовано"},
      {"timezone", "Часовой пояс"},
      {"currency", "Валюта"},
      {"warranty_days", "Гарантия, дней"},
      {"problem", "Неисправность"},
      {"intake_condition", "Состояние при приёме"},
      {"accessories", "Комплектность"},
      {"price_confirmed", "Цена подтверждена"},
      {"refunded", "Возвращено денег"},
      {"paid", "Оплачено"},
      {"charged", "Начислено"},
      {"credit", "К возврату"}
    };
    for (var p : pairs) TEXT.put(p[0], p[1]);
    String[][] values = {
      {"RECEIVED", "Принят"},
      {"DIAGNOSIS", "Диагностика"},
      {"WAITING_FOR_APPROVAL", "Согласование"},
      {"WAITING_FOR_PARTS", "Ожидание деталей"},
      {"IN_REPAIR", "В ремонте"},
      {"TESTING", "Тестирование"},
      {"COMPLETED", "Ремонт завершён"},
      {"READY_FOR_PICKUP", "Готов к выдаче"},
      {"DELIVERED", "Выдан"},
      {"CANCELLED", "Отменён"},
      {"OWNER", "Владелец"},
      {"MANAGER", "Администратор"},
      {"TECHNICIAN", "Мастер"},
      {"CASH", "Наличные"},
      {"CARD", "Карта"},
      {"BANK_TRANSFER", "Перевод"},
      {"OTHER", "Прочее"},
      {"true", "Да"},
      {"false", "Нет"},
      {"LOW", "Низкий"},
      {"NORMAL", "Обычный"},
      {"HIGH", "Высокий"},
      {"URGENT", "Срочный"},
      {"DRAFT", "Черновик"},
      {"ORDERED", "Заказано"},
      {"PARTIALLY_RECEIVED", "Частично получено"},
      {"POSTED", "Проведено"},
      {"IN", "Приход"},
      {"OUT", "Расход"}
    };
    for (var p : values) TEXT.put(p[0], p[1]);
    TEXT.put("paid_orders", "Оплаченные заказы");
    TEXT.put("period_month", "Месяц");
    TEXT.put("supplier_name", "Поставщик");
    TEXT.put("open_orders", "Открытые заказы");
    TEXT.put("total_paid", "Оплачено всего");
    TEXT.put("potential_duplicates", "Возможные дубликаты");
    TEXT.put("purchases", "Закупки");
    TEXT.put("devices", "Устройства");
  }

  public static String text(String key) {
    return TEXT.getOrDefault(key, key.replace('_', ' '));
  }

  public static String value(JsonNode row, String key) {
    var node = row.path(key);
    if (node.isNull() || node.isMissingNode()) return "";
    String value = node.asText();
    if (node.isNumber() && MONEY.contains(key)) {
      var format =
          java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("ru-KZ"));
      format.setCurrency(java.util.Currency.getInstance(currency));
      return format.format(node.decimalValue());
    }
    if (node.isNumber() && key.contains("quantity"))
      return node.decimalValue().stripTrailingZeros().toPlainString();
    if (key.endsWith("_at") || key.equals("occurred_at"))
      try {
        return Instant.parse(value)
            .atZone(Forms.zone)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
      } catch (Exception ignored) {
      }
    return TEXT.getOrDefault(value, value);
  }
}
