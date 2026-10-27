package com.fixflow.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiErrorTest {
  @Test
  void validationDetailsCannotBeChangedByTheCaller() {
    var fields = new HashMap<>(Map.of("phone", "Invalid phone"));
    var error =
        new ApiError(
            Instant.now(), 400, "VALIDATION_ERROR", "Invalid input", "/api/v1/customers", fields);
    fields.clear();
    assertEquals("Invalid phone", error.fieldErrors().get("phone"));
    assertThrows(UnsupportedOperationException.class, () -> error.fieldErrors().clear());
  }
}
