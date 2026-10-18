package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;

import com.moneymap.common.*;
import com.moneymap.ledger.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RulesTest {
  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "1.1", "1e3", "1000000000000", "01", "", "NaN"})
  void rejectsMoney(String input) {
    assertThrows(ApiException.class, () -> Rules.amount(input));
  }

  @Test
  void moneyBoundariesAndExactTotal() {
    assertEquals(1, Rules.amount("1"));
    long max = Rules.amount("999999999999");
    assertEquals(
        "9999999999990000",
        BigDecimal.valueOf(max).multiply(BigDecimal.valueOf(10000)).toPlainString());
  }

  @Test
  void calendarAndTimezone() {
    assertEquals(LocalDate.of(2024, 2, 29), Rules.date("2024-02-29"));
    assertThrows(ApiException.class, () -> Rules.date("2025-02-29"));
    assertThrows(ApiException.class, () -> Rules.date("2026-9-1"));
    Clock before = Clock.fixed(Instant.parse("2026-09-14T14:59:59Z"), ZoneId.of("Asia/Seoul"));
    assertEquals(LocalDate.of(2026, 9, 14), LocalDate.now(before));
    assertEquals(
        LocalDate.of(2026, 9, 15), LocalDate.now(Clock.offset(before, Duration.ofSeconds(1))));
  }

  @Test
  void requiresVersionAndRejectsWeakEtag() {
    assertEquals(428, assertThrows(ApiException.class, () -> Rules.version(null)).status);
    assertEquals(12, Rules.version("\"12\""));
    assertThrows(ApiException.class, () -> Rules.version("W/\"12\""));
  }

  @Test
  void coordinatesAndAccuracy() {
    var l = location("CURRENT", "90", "180", "1000", false);
    assertEquals("90.000000", l.normalized().latitude());
    assertThrows(
        ApiException.class, () -> location("CURRENT", "90.000001", "0", "1", false).normalized());
    assertThrows(
        ApiException.class, () -> location("CURRENT", "0.0000001", "0", "1", false).normalized());
    assertThrows(
        ApiException.class, () -> location("CURRENT", "0", "0", "1000.001", false).normalized());
    assertTrue(location("CURRENT", "0", "0", "1000.001", true).normalized().lowAccuracyAccepted());
  }

  @Test
  void noAddressClearsRegionAndNonCurrentMetadataRejected() {
    var l =
        new Writes.Location(
                "MAP", null, null, "37.5", "127", null, null, false, null, null, "11680", "서울특별시",
                "강남구")
            .normalized();
    assertNull(l.regionCode());
    assertNull(l.regionSido());
    assertThrows(ApiException.class, () -> location("MAP", "0", "0", "0", false).normalized());
  }

  @Test
  void searchNameRequiredAndExternalIdNotAllowedForMap() {
    assertThrows(
        ApiException.class,
        () ->
            new Writes.Location(
                    "SEARCH", null, null, "0", "0", null, null, false, null, null, null, null, null)
                .normalized());
    assertThrows(
        ApiException.class,
        () ->
            new Writes.Location(
                    "MAP", "p1", null, "0", "0", null, null, false, null, null, null, null, null)
                .normalized());
  }

  @Test
  void futureTransactionRejected() {
    var t =
        new Writes.Transaction("EXPENSE", "1", "KRW", UUID.randomUUID(), "2026-09-16", null, null);
    assertThrows(ApiException.class, () -> t.normalized(LocalDate.of(2026, 9, 15)));
  }

  @Test
  void percentageAndMonthlyZero() {
    assertEquals(33, Rules.percent(BigDecimal.ONE, BigDecimal.valueOf(3)));
    assertEquals(0, Rules.percent(BigDecimal.ZERO, BigDecimal.ZERO));
    assertNull(Queries.comparison(BigDecimal.ONE, BigDecimal.ZERO).get("growthPercent"));
    assertEquals(
        new BigDecimal("50.0"),
        Queries.comparison(new BigDecimal("150"), new BigDecimal("100")).get("growthPercent"));
  }

  private Writes.Location location(
      String source, String lat, String lon, String accuracy, boolean accepted) {
    return new Writes.Location(
        source,
        null,
        null,
        lat,
        lon,
        accuracy,
        "2026-09-15T01:00:00Z",
        accepted,
        null,
        null,
        null,
        null,
        null);
  }
}
