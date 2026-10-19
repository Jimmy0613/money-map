package com.moneymap.common;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Responses {
  public static Map<String, Object> data(Object data, HttpServletRequest r) {
    return Map.of("data", data, "meta", Map.of("requestId", id(r)));
  }

  public static String id(HttpServletRequest r) {
    return Objects.toString(r.getAttribute("requestId"), "unavailable");
  }

  public static Map<String, Object> error(String code, HttpServletRequest r) {
    return error(code, List.of(), r);
  }

  public static Map<String, Object> error(
      String code, List<ApiException.FieldError> fields, HttpServletRequest r) {
    return Map.of(
        "error",
        Map.of("code", code, "message", message(code), "fields", fields),
        "meta",
        Map.of("requestId", id(r)));
  }

  private static String message(String c) {
    return switch (c) {
      case "UNAUTHENTICATED", "ACCESS_EXPIRED", "REFRESH_REUSED" -> "로그인이 필요합니다.";
      case "NOT_FOUND" -> "항목을 찾을 수 없습니다.";
      case "VERSION_CONFLICT" -> "내용이 변경되었습니다. 최신 내용을 확인하세요.";
      case "SERVICE_UNAVAILABLE" -> "잠시 후 다시 시도하세요.";
      case "RECOVERY_REQUIRED" -> "서비스 안전 점검 중입니다. 잠시 후 다시 시도하세요.";
      case "EMAIL_EXISTS" -> "이미 사용 중인 이메일입니다.";
      case "REGISTRATION_CLOSED" -> "현재 회원가입을 받지 않습니다.";
      case "RATE_LIMITED" -> "요청이 많습니다. 잠시 후 다시 시도하세요.";
      case "DEMO_PROTECTED" -> "공개 체험 계정은 탈퇴할 수 없습니다.";
      case "DEMO_SESSION_EXPIRED" -> "체험 세션이 만료되었습니다. 다시 로그인하세요.";
      case "DEMO_QUOTA_EXCEEDED" -> "체험 계정의 등록 한도에 도달했습니다. 기존 기록의 조회와 수정은 계속할 수 있습니다.";
      default -> "요청을 처리할 수 없습니다. 입력과 현재 상태를 확인하세요.";
    };
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e, HttpServletRequest r) {
    r.setAttribute("errorCode", e.code);
    var response = ResponseEntity.status(e.status);
    if (e.status == 429 && !"DEMO_QUOTA_EXCEEDED".equals(e.code))
      response.header("Retry-After", "900");
    return response.body(error(e.code, e.fields, r));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> fields(MethodArgumentNotValidException e, HttpServletRequest r) {
    // Never serialize rejected values or interpolated validation messages.
    var errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    new ApiException.FieldError(
                        f.getField(),
                        Set.of("NotNull", "NotBlank").contains(Objects.toString(f.getCode(), ""))
                            ? "REQUIRED"
                            : "INVALID_VALUE",
                        "입력값의 형식과 범위를 확인하세요."))
            .distinct()
            .sorted(Comparator.comparing(ApiException.FieldError::field))
            .toList();
    return api(new ApiException(400, "VALIDATION_FAILED", errors), r);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    IllegalArgumentException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<?> validation(Exception e, HttpServletRequest r) {
    return api(ApiException.invalid(), r);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(Exception e, HttpServletRequest r) {
    return api(new ApiException(503, "SERVICE_UNAVAILABLE"), r);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e, HttpServletRequest r) {
    return api(new ApiException(500, "INTERNAL_ERROR"), r);
  }
}
