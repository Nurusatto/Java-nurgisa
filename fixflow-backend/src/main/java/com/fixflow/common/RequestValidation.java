package com.fixflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RequestValidation {
  public static class Fields extends ApiException {
    private final Map<String, String> fields;

    public Fields(Map<String, String> fields) {
      super(400, "VALIDATION_ERROR", "Проверьте заполненные поля");
      this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
      return fields;
    }
  }

  private final Validator validator;
  private final ObjectMapper json;

  public RequestValidation(Validator validator, ObjectMapper json) {
    this.validator = validator;
    this.json = json;
  }

  public <T> void check(Map<String, Object> body, Class<T> type) {
    T request;
    try {
      request = json.convertValue(body, type);
    } catch (IllegalArgumentException e) {
      throw new Fields(Map.of("request", "Некорректный формат полей"));
    }
    var errors = new TreeMap<String, String>();
    for (var violation : validator.validate(request))
      errors.put(violation.getPropertyPath().toString(), violation.getMessage());
    if (!errors.isEmpty()) throw new Fields(errors);
  }
}
