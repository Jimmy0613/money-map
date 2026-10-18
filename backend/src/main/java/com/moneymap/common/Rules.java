package com.moneymap.common;

import java.math.*;
import java.time.*;
import java.util.*;

public final class Rules {
  private Rules() {}

  public static <T> T field(String name, java.util.function.Supplier<T> check) {
    try {
      return check.get();
    } catch (ApiException e) {
      if (!"VALIDATION_FAILED".equals(e.code) || !e.fields.isEmpty()) throw e;
      throw ApiException.invalid(name, "INVALID_VALUE", "입력값의 형식과 범위를 확인하세요.");
    }
  }

  public static String text(String value, int max) {
    if (value == null) return null;
    String s = value.strip();
    if (s.length() > max) throw ApiException.invalid();
    return s.isEmpty() ? null : s;
  }

  public static String required(String value, int max) {
    String s = text(value, max);
    if (s == null) throw ApiException.invalid();
    return s;
  }

  public static String type(String s) {
    if (!Set.of("INCOME", "EXPENSE").contains(Objects.toString(s, "")))
      throw ApiException.invalid();
    return s;
  }

  public static long amount(String s) {
    if (s == null || !s.matches("[1-9][0-9]{0,11}"))
      throw ApiException.invalid("amount", "OUT_OF_RANGE", "1~999,999,999,999원의 정수를 입력하세요.");
    return Long.parseLong(s);
  }

  public static BigDecimal decimal(String s, int scale, BigDecimal max) {
    if (s == null || !s.matches("-?[0-9]+(\\.[0-9]{1," + scale + "})?"))
      throw ApiException.invalid();
    BigDecimal n = new BigDecimal(s);
    if (n.abs().compareTo(max) > 0) throw ApiException.invalid();
    return n;
  }

  public static LocalDate date(String s) {
    try {
      if (s == null || !s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw ApiException.invalid();
      return LocalDate.parse(s);
    } catch (DateTimeException e) {
      throw ApiException.invalid();
    }
  }

  public static UUID uuid(String s) {
    try {
      UUID id = UUID.fromString(s);
      if (!id.toString().equalsIgnoreCase(s)) throw ApiException.invalid();
      return id;
    } catch (Exception e) {
      throw ApiException.invalid();
    }
  }

  public static long version(String h) {
    if (h == null) throw new ApiException(428, "PRECONDITION_REQUIRED");
    if (!h.matches("\"[0-9]+\"")) throw ApiException.invalid();
    try {
      return Long.parseLong(h.substring(1, h.length() - 1));
    } catch (NumberFormatException e) {
      throw ApiException.invalid();
    }
  }

  public static int percent(BigDecimal value, BigDecimal total) {
    return total.signum() == 0
        ? 0
        : value
            .multiply(BigDecimal.valueOf(100))
            .divide(total, 0, RoundingMode.HALF_UP)
            .intValueExact();
  }

  public static Map<String, Object> map(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
    return m;
  }
}
