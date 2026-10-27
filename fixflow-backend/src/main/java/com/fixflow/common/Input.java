package com.fixflow.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class Input {
  private Input() {}

  public static String secret(Map<String, ?> body, String key, int max, boolean required) {
    String value = body.get(key) == null ? "" : body.get(key).toString();
    if (value.length() > max
        || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || required && value.isBlank()) throw ApiException.invalid("Проверьте пароль");
    return value;
  }

  public static String text(Map<String, ?> body, String key, int max, boolean required) {
    String value = body.get(key) == null ? "" : body.get(key).toString().trim();
    if ((required && value.isBlank()) || value.length() > max)
      throw ApiException.invalid("Поле «" + key + "»: проверьте длину и обязательность");
    return value;
  }

  public static UUID id(Object value) {
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (Exception e) {
      throw ApiException.invalid("Некорректный идентификатор");
    }
  }

  public static BigDecimal money(Object value) {
    try {
      var amount = new BigDecimal(String.valueOf(value));
      if (amount.signum() < 0
          || amount.precision() > 18
          || amount.scale() > 2
          || amount.compareTo(new BigDecimal("9999999999999999.99")) > 0)
        throw new IllegalArgumentException();
      return amount.setScale(2);
    } catch (Exception e) {
      throw ApiException.invalid("Сумма должна быть неотрицательной, с точностью до двух знаков");
    }
  }

  public static BigDecimal quantity(Object value) {
    try {
      var q = new BigDecimal(String.valueOf(value));
      if (q.signum() <= 0
          || q.scale() > 3
          || q.precision() > 12
          || q.compareTo(new BigDecimal("999999999.999")) > 0) throw new IllegalArgumentException();
      return q;
    } catch (Exception e) {
      throw ApiException.invalid(
          "Количество должно быть положительным, до трёх знаков после запятой");
    }
  }

  public static String choice(Object value, String... choices) {
    String s = String.valueOf(value);
    for (String choice : choices) if (choice.equals(s)) return s;
    throw ApiException.invalid("Недопустимое значение: " + s);
  }

  public static Instant instant(Object value) {
    return value == null || value.toString().isBlank() ? null : Instant.parse(value.toString());
  }

  public static BigDecimal decimal(Object value) {
    return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
  }

  public static BigDecimal rounded(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  public static long version(Map<String, ?> body) {
    if (!body.containsKey("version")) throw ApiException.invalid("Передайте версию записи");
    return Long.parseLong(body.get("version").toString());
  }
}
