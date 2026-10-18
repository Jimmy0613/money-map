package com.moneymap.common;

import java.util.List;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;
  public final List<FieldError> fields;

  public record FieldError(String field, String code, String message) {}

  public ApiException(int status, String code) {
    this(status, code, List.of());
  }

  public ApiException(int status, String code, List<FieldError> fields) {
    super(code);
    this.status = status;
    this.code = code;
    this.fields = List.copyOf(fields);
  }

  public static ApiException invalid(String field, String code, String message) {
    return new ApiException(
        400, "VALIDATION_FAILED", List.of(new FieldError(field, code, message)));
  }

  public static ApiException invalid() {
    return new ApiException(400, "VALIDATION_FAILED");
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND");
  }
}
