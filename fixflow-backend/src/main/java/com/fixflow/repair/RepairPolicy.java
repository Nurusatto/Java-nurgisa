package com.fixflow.repair;

import com.fixflow.common.ApiException;
import java.util.*;

public final class RepairPolicy {
  private RepairPolicy() {}

  private static final Map<String, Set<String>> NEXT =
      Map.of(
          "RECEIVED",
          Set.of("DIAGNOSIS", "CANCELLED"),
          "DIAGNOSIS",
          Set.of("WAITING_FOR_APPROVAL", "CANCELLED"),
          "WAITING_FOR_APPROVAL",
          Set.of("WAITING_FOR_PARTS", "IN_REPAIR", "CANCELLED"),
          "WAITING_FOR_PARTS",
          Set.of("IN_REPAIR", "WAITING_FOR_APPROVAL", "CANCELLED"),
          "IN_REPAIR",
          Set.of("WAITING_FOR_PARTS", "WAITING_FOR_APPROVAL", "TESTING", "CANCELLED"),
          "TESTING",
          Set.of("IN_REPAIR", "COMPLETED", "CANCELLED"),
          "COMPLETED",
          Set.of("READY_FOR_PICKUP"),
          "READY_FOR_PICKUP",
          Set.of("DELIVERED"),
          "DELIVERED",
          Set.of(),
          "CANCELLED",
          Set.of());

  public static Set<String> next(String status) {
    return NEXT.getOrDefault(status, Set.of());
  }

  public static void check(String from, String to, String role) {
    if (!next(from).contains(to))
      throw ApiException.conflict("Недопустимый переход: " + from + " → " + to);
    if (role.equals("TECHNICIAN")
        && !Set.of(
                "DIAGNOSIS",
                "WAITING_FOR_APPROVAL",
                "WAITING_FOR_PARTS",
                "IN_REPAIR",
                "TESTING",
                "COMPLETED")
            .contains(to)) throw ApiException.forbidden();
  }

  public static boolean frozen(String status) {
    return Set.of("COMPLETED", "READY_FOR_PICKUP", "DELIVERED", "CANCELLED").contains(status);
  }
}
