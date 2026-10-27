package com.fixflow.common;

public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException invalid(String message) {
    return new ApiException(400, "VALIDATION_ERROR", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, "BUSINESS_CONFLICT", message);
  }

  public static ApiException forbidden() {
    return new ApiException(403, "ACCESS_DENIED", "Недостаточно прав для операции");
  }
}
