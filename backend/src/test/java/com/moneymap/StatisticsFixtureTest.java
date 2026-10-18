package com.moneymap;

import static com.moneymap.common.Rules.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moneymap.ledger.*;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.util.LinkedMultiValueMap;

class StatisticsFixtureTest {
  LedgerRepository repository = mock(LedgerRepository.class);
  Cursors cursors = mock(Cursors.class);
  Queries queries = new Queries(repository, cursors);
  UUID user = UUID.randomUUID();

  @BeforeEach
  void fixture() {
    when(repository.user(user, false))
        .thenReturn(map("timezone", "Asia/Seoul", "data_revision", 5L));
    when(repository.query(anyString(), any(Object[].class)))
        .thenAnswer(
            inv -> {
              String sql = inv.getArgument(0);
              if (sql.contains("FROM region_dictionary"))
                return List.of(
                    map("region_key", "CODE:11680", "sido_name", "서울특별시", "sigungu_name", "강남구"));
              List<Map<String, Object>> rows =
                  new ArrayList<>(
                      List.of(
                          row(1, "EXPENSE", 12000, "food", "p1", "CODE:11680"),
                          row(2, "EXPENSE", 4000, "food", "p1", "CODE:11680"),
                          row(3, "EXPENSE", 3000, "transport", null, "UNKNOWN"),
                          row(4, "EXPENSE", 10000, "shopping", null, null),
                          row(5, "INCOME", 50000, "other-income", "p1", "CODE:11680"),
                          row(6, "INCOME", 100000, "salary", null, null)));
              if (Arrays.asList(inv.getArguments()).contains("EXPENSE"))
                rows.removeIf(r -> r.get("type").equals("INCOME"));
              return rows;
            });
  }

  Map<String, Object> row(
      int id, String type, long amount, String category, String place, String region) {
    return map(
        "id",
        new UUID(0, id),
        "type",
        type,
        "amount",
        amount,
        "category_id",
        category,
        "category_name",
        category,
        "transaction_date",
        Date.valueOf("2026-09-" + (16 - id)),
        "created_at",
        Timestamp.valueOf("2026-09-15 01:00:00"),
        "source",
        region == null ? null : place == null ? "MAP" : "SEARCH",
        "place_id",
        place,
        "region_key",
        region,
        "latitude",
        new BigDecimal("37.500000"),
        "longitude",
        new BigDecimal("127.000000"),
        "place_name",
        "합성 장소",
        "road_address",
        null,
        "parcel_address",
        null);
  }

  LinkedMultiValueMap<String, String> filter() {
    var q = new LinkedMultiValueMap<String, String>();
    q.set("from", "2026-09-01");
    q.set("to", "2026-09-30");
    return q;
  }

  @Test
  void F0Summary() {
    var r = queries.statistics(user, "summary", filter());
    assertEquals("150000", r.get("incomeTotal"));
    assertEquals("29000", r.get("expenseTotal"));
    assertEquals("121000", r.get("balance"));
    assertEquals(2, r.get("incomeCount"));
    assertEquals(4, r.get("expenseCount"));
  }

  @Test
  void F0Categories() {
    var r = queries.statistics(user, "categories", filter());
    assertEquals("29000", r.get("expenseTotal"));
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) r.get("items");
    assertEquals(
        List.of("16000", "10000", "3000"), items.stream().map(i -> i.get("amount")).toList());
  }

  @Test
  void F0PlacesSeparateIncomeAndUnidentified() {
    var r = queries.statistics(user, "places", filter());
    assertEquals("16000", r.get("identifiedExpenseTotal"));
    assertEquals(map("amount", "3000", "count", 1), r.get("unidentifiedLocation"));
    assertEquals(map("amount", "10000", "count", 1), r.get("locationless"));
  }

  @Test
  void F0RegionsExcludeLocationlessIncludeUnknown() {
    var r = queries.statistics(user, "regions", filter());
    assertEquals("19000", r.get("locatedExpenseTotal"));
    assertEquals(1, r.get("unclassifiedCount"));
    assertEquals(1, r.get("locationlessCount"));
  }
}
