package com.moneymap.ledger;

import static com.moneymap.common.Rules.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moneymap.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public final class Writes {
  private Writes() {}

  public record Transaction(
      @NotNull String type,
      @NotNull String amount,
      @NotNull String currency,
      @NotNull UUID categoryId,
      @NotNull String transactionDate,
      @JsonProperty(required = true) String memo,
      @Valid @JsonProperty(required = true) Location location) {
    public Transaction normalized(LocalDate today) {
      field("type", () -> Rules.type(type));
      Rules.amount(amount);
      if (!"KRW".equals(currency))
        throw ApiException.invalid("currency", "INVALID_VALUE", "KRW만 지원합니다.");
      if (field("transactionDate", () -> date(transactionDate)).isAfter(today))
        throw ApiException.invalid("transactionDate", "OUT_OF_RANGE", "미래 날짜는 입력할 수 없습니다.");
      return new Transaction(
          type,
          amount,
          currency,
          categoryId,
          transactionDate,
          field("memo", () -> text(memo, 500)),
          location == null ? null : field("location", location::normalized));
    }
  }

  public record Location(
      @NotNull String source,
      String externalPlaceId,
      String placeName,
      @NotNull String latitude,
      @NotNull String longitude,
      String accuracyM,
      String capturedAt,
      @NotNull Boolean lowAccuracyAccepted,
      String roadAddress,
      String parcelAddress,
      String regionCode,
      String regionSido,
      String regionSigungu) {
    public Location normalized() {
      if (!Set.of("CURRENT", "SEARCH", "MAP").contains(source)) throw ApiException.invalid();
      BigDecimal
          lat = field("location.latitude", () -> decimal(latitude, 6, BigDecimal.valueOf(90))),
          lon = field("location.longitude", () -> decimal(longitude, 6, BigDecimal.valueOf(180)));
      String ext = text(externalPlaceId, 100),
          name = text(placeName, 200),
          road = text(roadAddress, 500),
          parcel = text(parcelAddress, 500),
          sido = text(regionSido, 50),
          sigungu = text(regionSigungu, 100),
          code = regionCode;
      if (code != null && !code.matches("[0-9]{5}")) throw ApiException.invalid();
      if (road == null && parcel == null) {
        sido = null;
        sigungu = null;
        code = null;
      }
      String accuracy = null, captured = null;
      if ("CURRENT".equals(source)) {
        if (ext != null || capturedAt == null || accuracyM == null) throw ApiException.invalid();
        BigDecimal a = decimal(accuracyM, 3, new BigDecimal("999999999.999"));
        if (a.signum() < 0
            || (a.compareTo(BigDecimal.valueOf(1000)) > 0
                && !Boolean.TRUE.equals(lowAccuracyAccepted))) throw ApiException.invalid();
        try {
          Instant i = Instant.parse(capturedAt);
          if (i.isAfter(Instant.now().plusSeconds(300))) throw ApiException.invalid();
          captured = i.toString();
        } catch (DateTimeException e) {
          throw ApiException.invalid();
        }
        accuracy = a.stripTrailingZeros().toPlainString();
      } else if (accuracyM != null
          || capturedAt != null
          || !Boolean.FALSE.equals(lowAccuracyAccepted)
          || ("MAP".equals(source) && ext != null)) throw ApiException.invalid();
      if ("SEARCH".equals(source) && name == null) throw ApiException.invalid();
      return new Location(
          source,
          ext,
          name,
          lat.setScale(6).toPlainString(),
          lon.setScale(6).toPlainString(),
          accuracy,
          captured,
          lowAccuracyAccepted,
          road,
          parcel,
          code,
          sido,
          sigungu);
    }
  }

  public record Category(@NotNull String type, @NotNull String name, String icon, String color) {}

  public static void style(String icon, String color) {
    if (icon != null
        && !Set.of(
                "food",
                "cafe",
                "transport",
                "shopping",
                "home",
                "life",
                "medical",
                "culture",
                "travel",
                "education",
                "salary",
                "money",
                "business",
                "finance",
                "other")
            .contains(icon)) throw ApiException.invalid();
    if (color != null && !color.matches("#[0-9a-fA-F]{6}")) throw ApiException.invalid();
  }
}
