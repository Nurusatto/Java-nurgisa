package com.fixflow;

import static org.junit.jupiter.api.Assertions.*;

import com.fixflow.common.ApiException;
import com.fixflow.common.Input;
import com.fixflow.repair.RepairPolicy;
import org.junit.jupiter.api.Test;

class RepairPolicyTest {
  @Test
  void cannotDeliverFromDiagnosisOrReopenDeliveredOrder() {
    assertThrows(ApiException.class, () -> RepairPolicy.check("DIAGNOSIS", "DELIVERED", "OWNER"));
    assertThrows(ApiException.class, () -> RepairPolicy.check("DELIVERED", "IN_REPAIR", "OWNER"));
  }

  @Test
  void technicianCannotCancelOrIssueDevice() {
    assertThrows(
        ApiException.class, () -> RepairPolicy.check("RECEIVED", "CANCELLED", "TECHNICIAN"));
    assertThrows(
        ApiException.class,
        () -> RepairPolicy.check("READY_FOR_PICKUP", "DELIVERED", "TECHNICIAN"));
  }

  @Test
  void testingCanReturnToRepairAndTheNormalPathIsAllowed() {
    assertDoesNotThrow(() -> RepairPolicy.check("TESTING", "IN_REPAIR", "TECHNICIAN"));
    assertDoesNotThrow(() -> RepairPolicy.check("TESTING", "COMPLETED", "TECHNICIAN"));
  }

  @Test
  void monetaryValidationRejectsNegativeAndExcessPrecision() {
    assertThrows(ApiException.class, () -> Input.money("-0.01"));
    assertThrows(ApiException.class, () -> Input.money("1.001"));
    assertThrows(ApiException.class, () -> Input.quantity("0"));
    assertEquals("10.20", Input.money("10.2").toPlainString());
  }

  @Test
  void passwordWhitespaceIsPreserved() {
    assertEquals(
        "  password with spaces  ",
        Input.secret(
            java.util.Map.of("password", "  password with spaces  "), "password", 100, true));
  }
}
