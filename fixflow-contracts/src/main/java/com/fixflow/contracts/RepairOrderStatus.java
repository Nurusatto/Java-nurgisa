package com.fixflow.contracts;

public enum RepairOrderStatus {
  RECEIVED,
  DIAGNOSIS,
  WAITING_FOR_APPROVAL,
  WAITING_FOR_PARTS,
  IN_REPAIR,
  TESTING,
  COMPLETED,
  READY_FOR_PICKUP,
  DELIVERED,
  CANCELLED
}
