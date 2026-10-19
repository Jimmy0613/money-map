package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;

import com.moneymap.auth.*;
import com.moneymap.ledger.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
class PostgresIntegrationTest {
  // Business contract tests isolate the recovery authority; RecoveryAuthorityIntegrationTest
  // exercises the actual gate, signed HTTP protocol, and PostgreSQL binding separately.
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.moneymap.common.RecoveryGate recoveryGate;
  @org.junit.jupiter.api.BeforeEach
  void permitBusinessFixture() {
    org.mockito.Mockito.when(recoveryGate.allows()).thenReturn(true);
  }

  @Container static TestPostgres postgres = new TestPostgres();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add(
        "app.cursor-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add("app.cookie-secure", () -> true);
    r.add("app.origin", () -> "https://example.invalid");
  }

  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired LedgerService ledger;
  @Autowired Queries queries;
  @Autowired JdbcTemplate db;

  @Test
  void migrationCrudIsolationAndDeletionLedger() {
    String email = UUID.randomUUID() + "@example.invalid", password = Tokens.random() + "A1";
    UUID user =
        (UUID)
            auth.signup(new AuthService.Signup(email, password, password, true, "v1"), "fixture-a")
                .get("id");
    assertEquals(
        16,
        db.queryForObject("SELECT count(*) FROM categories WHERE user_id=?", Integer.class, user));
    assertEquals(11, items(ledger.categories(user, "EXPENSE", false)).size());
    assertEquals(5, items(ledger.categories(user, "INCOME", false)).size());
    assertEquals(
        409,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () ->
                    auth.signup(
                        new AuthService.Signup(
                            "  " + email.toUpperCase(Locale.ROOT) + "  ",
                            password,
                            password,
                            true,
                            "v1"),
                        "fixture-duplicate"))
            .status);
    assertEquals(
        1, db.queryForObject("SELECT count(*) FROM users WHERE email=?", Integer.class, email));
    var badPassword =
        assertThrows(
            com.moneymap.common.ApiException.class,
            () -> auth.login(new AuthService.Login(email, "WrongSynthetic123"), "fixture-wrong"));
    var unknown =
        assertThrows(
            com.moneymap.common.ApiException.class,
            () ->
                auth.login(
                    new AuthService.Login(
                        UUID.randomUUID() + "@example.invalid", "WrongSynthetic123"),
                    "fixture-unknown"));
    assertEquals(401, badPassword.status);
    assertEquals(badPassword.code, unknown.code);
    var credentials = auth.login(new AuthService.Login(email, password), "fixture-a");
    assertEquals(user, auth.authenticate(credentials.access()).user());
    UUID category =
        db.queryForObject(
            "SELECT id FROM categories WHERE user_id=? AND name='식비'", UUID.class, user);
    var body =
        new Writes.Transaction("EXPENSE", "12000", "KRW", category, "2026-09-15", null, null);
    UUID key = UUID.randomUUID();
    var created = ledger.create(user, key, body);
    UUID id = (UUID) created.transaction().get("id");
    assertFalse(created.replay());
    assertTrue(ledger.create(user, key, body).replay());
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                com.moneymap.common.ApiException.class,
                () ->
                    ledger.create(
                        user,
                        key,
                        new Writes.Transaction(
                            "EXPENSE", "12001", "KRW", category, "2026-09-15", null, null)))
            .code);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM transaction_locations WHERE user_id=?", Integer.class, user));
    UUID other =
        (UUID)
            auth.signup(
                    new AuthService.Signup(
                        UUID.randomUUID() + "@example.invalid", password, password, true, "v1"),
                    "fixture-b")
                .get("id");
    assertThrows(com.moneymap.common.ApiException.class, () -> ledger.get(other, id));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            db.update(
                "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date)"
                    + " VALUES (?,?, 'EXPENSE',1,?,DATE '2026-09-15')",
                UUID.randomUUID(),
                other,
                category));
    var filter = new LinkedMultiValueMap<String, String>();
    filter.set("from", "2026-09-01");
    filter.set("to", "2026-09-30");
    assertEquals("12000", queries.statistics(user, "summary", filter).get("expenseTotal"));
    ledger.replace(
        user,
        id,
        "\"0\"",
        new Writes.Transaction("EXPENSE", "15000", "KRW", category, "2026-09-15", null, null));
    assertEquals("15000", queries.statistics(user, "summary", filter).get("expenseTotal"));
    assertThrows(com.moneymap.common.ApiException.class, () -> ledger.delete(user, id, "\"0\""));
    ledger.delete(user, id, "\"1\"");
    assertEquals(
        "OPERATION_DELETED",
        assertThrows(com.moneymap.common.ApiException.class, () -> ledger.create(user, key, body))
            .code);
    auth.delete(user, new AuthService.DeleteAccount(password, "DELETE"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM privacy.deletion_ledger WHERE user_id=?", Integer.class, user));
    assertEquals(
        0,
        db.queryForObject("SELECT count(*) FROM categories WHERE user_id=?", Integer.class, user));
    assertThrows(
        com.moneymap.common.ApiException.class, () -> auth.authenticate(credentials.access()));
  }

  @Test
  void refreshReuseRevokesFamily() {
    String password = Tokens.random() + "A1", email = UUID.randomUUID() + "@example.invalid";
    auth.signup(new AuthService.Signup(email, password, password, true, "v1"), "fixture-refresh");
    var first = auth.login(new AuthService.Login(email, password), "fixture-refresh");
    var next = auth.refresh(first.refresh());
    assertThrows(com.moneymap.common.ApiException.class, () -> auth.refresh(first.refresh()));
    assertThrows(com.moneymap.common.ApiException.class, () -> auth.authenticate(next.access()));
  }

  private record Fixture(UUID user, String password, String email) {}

  private Fixture account() {
    String email = UUID.randomUUID() + "@example.invalid", password = Tokens.random() + "A1";
    UUID user =
        (UUID)
            auth.signup(
                    new AuthService.Signup(email, password, password, true, "v1"),
                    UUID.randomUUID().toString())
                .get("id");
    return new Fixture(user, password, email);
  }

  private UUID category(UUID user, String type, String name) {
    return db.queryForObject(
        "SELECT id FROM categories WHERE user_id=? AND type=? AND name=?",
        UUID.class,
        user,
        type,
        name);
  }

  private Writes.Location search(String code) {
    return new Writes.Location(
        "SEARCH",
        "fixture-p1",
        "합성 장소",
        "37.500000",
        "127.000000",
        null,
        null,
        false,
        "합성 주소",
        null,
        code,
        "서울특별시",
        "강남구");
  }

  private UUID create(
      UUID user, String type, String amount, String name, String day, Writes.Location location) {
    return (UUID)
        ledger
            .create(
                user,
                UUID.randomUUID(),
                new Writes.Transaction(
                    type, amount, "KRW", category(user, type, name), day, null, location))
            .transaction()
            .get("id");
  }

  private LinkedMultiValueMap<String, String> september() {
    var q = new LinkedMultiValueMap<String, String>();
    q.set("from", "2026-09-01");
    q.set("to", "2026-09-30");
    return q;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> items(Map<String, Object> response) {
    return (List<Map<String, Object>>) response.get("items");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> object(Map<String, Object> response, String key) {
    return (Map<String, Object>) response.get(key);
  }

  private LinkedMultiValueMap<String, String> bbox() {
    var q = september();
    q.set("south", "37");
    q.set("north", "38");
    q.set("west", "126");
    q.set("east", "128");
    q.set("level", "5");
    return q;
  }

  @Test
  void monthlyUnionIncludesPreviousOnlyAndCurrentOnlyGroups() {
    var a = account();
    create(a.user, "EXPENSE", "8000", "식비", "2026-08-20", search("11680"));
    create(a.user, "EXPENSE", "3000", "교통", "2026-09-01",
        new Writes.Location("MAP", null, null, "37.1", "127.1", null, null, false, null, null, null, null, null));
    var q = new LinkedMultiValueMap<String, String>();
    q.set("month", "2026-09");
    var result = queries.statistics(a.user, "monthly-comparison", q);
    assertEquals(Set.of(category(a.user,"EXPENSE","식비").toString(), category(a.user,"EXPENSE","교통").toString()),
        ((List<Map<String,Object>>)result.get("categories")).stream().map(r -> r.get("key")).collect(java.util.stream.Collectors.toSet()));
    var groups = (List<Map<String,Object>>)result.get("regions");
    assertEquals(Set.of("CODE:11680", "UNKNOWN"), groups.stream().map(r -> r.get("key")).collect(java.util.stream.Collectors.toSet()));
    var added = groups.stream().filter(r -> r.get("key").equals("UNKNOWN")).findFirst().orElseThrow();
    assertEquals("3000", added.get("current"));
    assertEquals("0", added.get("previous"));
    assertNull(added.get("growthPercent"));
    assertEquals("NOT_COMPARABLE", added.get("status"));
    var removed = groups.stream().filter(r -> r.get("key").equals("CODE:11680")).findFirst().orElseThrow();
    assertEquals("0", removed.get("current"));
    assertEquals("8000", removed.get("previous"));
    assertEquals("-8000", removed.get("difference"));
  }

  @Test
  void anotherOwnersPlaceCannotBeLinkedEvenDirectlyInDatabase() {
    var a = account(); var b = account();
    UUID own = create(a.user,"EXPENSE","1000","식비","2026-09-01",search("11680"));
    UUID other = create(b.user,"EXPENSE","2000","식비","2026-09-01",search("11680"));
    UUID foreignPlace = db.queryForObject("SELECT place_id FROM transaction_locations WHERE transaction_id=?", UUID.class, other);
    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
        () -> db.update("UPDATE transaction_locations SET place_id=? WHERE transaction_id=?", foreignPlace, own));
    var q = september(); q.set("placeId", foreignPlace.toString());
    assertEquals(404, assertThrows(com.moneymap.common.ApiException.class, () -> queries.list(a.user,q)).status);
    assertNotEquals(foreignPlace, db.queryForObject("SELECT place_id FROM transaction_locations WHERE transaction_id=?", UUID.class, own));
  }

  @Test
  void F0ActualDatabaseQueriesAndCursor() {
    var a = account();
    var b = account();
    db.update(
        "INSERT INTO region_aliases(id,region_key,sido_normalized,sigungu_normalized,valid_from)"
            + " VALUES (?,'CODE:11680','서울특별시','강남구',DATE '2026-09-01') ON CONFLICT DO NOTHING",
        UUID.randomUUID());
    UUID t1 = create(a.user, "EXPENSE", "12000", "식비", "2026-09-15", search("11680"));
    create(a.user, "EXPENSE", "4000", "식비", "2026-09-14", search(null));
    create(
        a.user,
        "EXPENSE",
        "3000",
        "교통",
        "2026-09-13",
        new Writes.Location(
            "MAP",
            null,
            null,
            "37.510000",
            "127.010000",
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null));
    create(a.user, "EXPENSE", "10000", "쇼핑", "2026-09-12", null);
    create(a.user, "INCOME", "50000", "기타", "2026-09-11", search("11680"));
    create(a.user, "INCOME", "100000", "급여", "2026-09-10", null);
    create(a.user, "EXPENSE", "8000", "식비", "2026-08-20", search("11680"));
    UUID other = create(b.user, "EXPENSE", "99999", "식비", "2026-09-15", search("11680"));
    assertEquals(
        404,
        assertThrows(com.moneymap.common.ApiException.class, () -> ledger.get(a.user, other))
            .status);
    var summary = queries.statistics(a.user, "summary", september());
    assertEquals("29000", summary.get("expenseTotal"));
    assertEquals("150000", summary.get("incomeTotal"));
    assertEquals("121000", summary.get("balance"));
    var categoryStats = queries.statistics(a.user, "categories", september());
    assertEquals(
        List.of("16000", "10000", "3000"),
        items(categoryStats).stream().map(r -> r.get("amount")).toList());
    assertEquals(
        List.of(55, 34, 10), items(categoryStats).stream().map(r -> r.get("percent")).toList());
    var places = queries.statistics(a.user, "places", september());
    assertEquals("16000", places.get("identifiedExpenseTotal"));
    assertEquals(1, items(places).size());
    assertEquals("16000", items(places).getFirst().get("amount"));
    assertEquals(2, items(places).getFirst().get("count"));
    assertEquals(Map.of("amount", "3000", "count", 1), object(places, "unidentifiedLocation"));
    assertEquals(Map.of("amount", "10000", "count", 1), object(places, "locationless"));
    var placeFilter = september();
    placeFilter.set("placeId", items(places).getFirst().get("placeId").toString());
    var atPlace = queries.statistics(a.user, "categories", placeFilter);
    assertEquals("16000", atPlace.get("expenseTotal"));
    assertEquals(1, items(atPlace).size());
    assertEquals(100, items(atPlace).getFirst().get("percent"));
    assertEquals("16000", items(atPlace).getFirst().get("amount"));
    placeFilter.set("type", "EXPENSE");
    var placeRows = items(queries.list(a.user, placeFilter));
    assertEquals(2, placeRows.size());
    assertTrue(placeRows.stream().allMatch(row -> row.get("type").equals("EXPENSE")));
    var regions = queries.statistics(a.user, "regions", september());
    assertEquals("19000", regions.get("locatedExpenseTotal"));
    assertEquals(2, items(regions).size());
    assertEquals("16000", items(regions).getFirst().get("amount"));
    assertEquals("CODE:11680", items(regions).getFirst().get("regionKey"));
    assertEquals("UNKNOWN", items(regions).getLast().get("regionKey"));
    assertEquals("3000", items(regions).getLast().get("amount"));
    var monthly = new LinkedMultiValueMap<String, String>();
    monthly.set("month", "2026-09");
    var comparison = queries.statistics(a.user, "monthly-comparison", monthly);
    assertEquals("29000", object(comparison, "total").get("current"));
    assertEquals("8000", object(comparison, "total").get("previous"));
    assertEquals("21000", object(comparison, "total").get("difference"));
    assertEquals(
        new java.math.BigDecimal("262.5"), object(comparison, "total").get("growthPercent"));
    var map = queries.markers(a.user, bbox(), false);
    assertEquals("19000", map.get("viewportExpenseTotal"));
    assertEquals(3L, ((Number) map.get("viewportCount")).longValue());
    assertEquals("29000", map.get("periodExpenseTotal"));
    assertEquals(2, items(map).size());
    var marker =
        items(map).stream()
            .filter(r -> r.get("markerKey").toString().startsWith("place:"))
            .findFirst()
            .orElseThrow();
    var detail = bbox();
    detail.set("markerKey", marker.get("markerKey").toString());
    detail.set("revision", map.get("revision").toString());
    assertEquals("16000", queries.markers(a.user, detail, true).get("amount"));
    var q = september();
    q.set("size", "2");
    Set<Object> found = new HashSet<>();
    String firstCursor = null;
    do {
      var page = queries.list(a.user, q);
      for (var row : items(page)) assertTrue(found.add(row.get("id")));
      String next = (String) object(page, "page").get("nextCursor");
      if (firstCursor == null) firstCursor = next;
      if (next == null) break;
      q.set("cursor", next);
    } while (true);
    assertEquals(6, found.size());
    q.set("cursor", firstCursor);
    assertEquals(
        400,
        assertThrows(com.moneymap.common.ApiException.class, () -> queries.list(b.user, q)).status);
    ledger.replace(
        a.user,
        t1,
        "\"0\"",
        new Writes.Transaction(
            "EXPENSE",
            "15000",
            "KRW",
            category(a.user, "EXPENSE", "식비"),
            "2026-09-15",
            null,
            null));
    assertEquals(
        "CURSOR_STALE",
        assertThrows(com.moneymap.common.ApiException.class, () -> queries.list(a.user, q)).code);
    assertNull(ledger.get(a.user, t1).get("location"));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM transaction_locations WHERE transaction_id=?",
            Integer.class,
            t1));
    assertEquals("32000", queries.statistics(a.user, "summary", september()).get("expenseTotal"));
    ledger.delete(a.user, t1, "\"1\"");
    assertEquals("17000", queries.statistics(a.user, "summary", september()).get("expenseTotal"));
    assertEquals(
        404,
        assertThrows(
                com.moneymap.common.ApiException.class, () -> ledger.delete(a.user, t1, "\"1\""))
            .status);
    assertEquals("17000", queries.statistics(a.user, "summary", september()).get("expenseTotal"));
  }

  @Test
  void concurrentIdempotencyAndConditionalUpdate() throws Exception {
    var a = account();
    UUID key = UUID.randomUUID();
    var body =
        new Writes.Transaction(
            "EXPENSE", "1", "KRW", category(a.user, "EXPENSE", "식비"), "2026-09-15", null, null);
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var one =
          pool.submit(
              () -> {
                start.await();
                return ledger.create(a.user, key, body);
              });
      var two =
          pool.submit(
              () -> {
                start.await();
                return ledger.create(a.user, key, body);
              });
      start.countDown();
      var x = one.get();
      var y = two.get();
      assertEquals(x.transaction().get("id"), y.transaction().get("id"));
      assertNotEquals(x.replay(), y.replay());
      UUID id = (UUID) x.transaction().get("id");
      var ready = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Integer> update =
          () -> {
            ready.await();
            try {
              ledger.replace(a.user, id, "\"0\"", body);
              return 200;
            } catch (com.moneymap.common.ApiException e) {
              return e.status;
            }
          };
      var p = pool.submit(update);
      var q = pool.submit(update);
      ready.countDown();
      assertEquals(Set.of(200, 412), Set.of(p.get(), q.get()));
    }
  }

  @Test
  void categoryArchiveAndPrecision() {
    var a = account();
    UUID id =
        (UUID)
            ledger
                .createCategory(a.user, new Writes.Category("EXPENSE", "합성 사용자 분류", null, null))
                .get("id");
    var t =
        ledger.create(
            a.user,
            UUID.randomUUID(),
            new Writes.Transaction("EXPENSE", "1", "KRW", id, "2026-09-15", null, null));
    ledger.patchCategory(a.user, id, "\"0\"", Map.of("name", "새 분류"));
    ledger.patchCategory(a.user, id, "\"1\"", Map.of("archived", true));
    assertEquals(
        409,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () ->
                    ledger.create(
                        a.user,
                        UUID.randomUUID(),
                        new Writes.Transaction(
                            "EXPENSE", "1", "KRW", id, "2026-09-15", null, null)))
            .status);
    ledger.replace(
        a.user,
        (UUID) t.transaction().get("id"),
        "\"0\"",
        new Writes.Transaction("EXPENSE", "2", "KRW", id, "2026-09-15", null, null));
    UUID max = category(a.user, "EXPENSE", "식비");
    db.update(
        "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT"
            + " gen_random_uuid(),?,'EXPENSE',999999999999,?,DATE '2026-08-01' FROM"
            + " generate_series(1,10000)",
        a.user,
        max);
    var q = new LinkedMultiValueMap<String, String>();
    q.set("from", "2026-08-01");
    q.set("to", "2026-08-31");
    assertEquals("9999999999990000", queries.statistics(a.user, "summary", q).get("expenseTotal"));
  }

  @Test
  void deletionLedgerFailureRollsBackUserDelete() {
    var a = account();
    create(a.user, "EXPENSE", "10", "식비", "2026-09-15", null);
    db.execute(
        "CREATE FUNCTION privacy.test_reject_ledger() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " RAISE EXCEPTION 'synthetic failure'; END $$");
    db.execute(
        "CREATE TRIGGER test_reject_ledger BEFORE INSERT ON privacy.deletion_ledger FOR EACH ROW"
            + " EXECUTE FUNCTION privacy.test_reject_ledger()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> auth.delete(a.user, new AuthService.DeleteAccount(a.password, "DELETE")));
      assertEquals(
          1, db.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, a.user));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT count(*) FROM transactions WHERE user_id=?", Integer.class, a.user));
    } finally {
      db.execute("DROP TRIGGER test_reject_ledger ON privacy.deletion_ledger");
      db.execute("DROP FUNCTION privacy.test_reject_ledger()");
    }
  }

  @Test
  void mapCapAndAntimeridian() {
    var a = account();
    UUID category = category(a.user, "EXPENSE", "식비");
    db.update(
        "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT"
            + " gen_random_uuid(),?,'EXPENSE',1,?,DATE '2026-09-15' FROM generate_series(1,501)",
        a.user,
        category);
    db.update(
        "INSERT INTO transaction_locations(transaction_id,user_id,source,latitude,longitude) SELECT"
            + " id,user_id,'MAP',37+row_number() OVER(ORDER BY id)*0.000001,127 FROM transactions"
            + " WHERE user_id=?",
        a.user);
    var capped = queries.markers(a.user, bbox(), false);
    assertEquals(true, capped.get("requiresNarrowing"));
    assertTrue(items(capped).isEmpty());
    assertEquals("501", capped.get("viewportExpenseTotal"));
    var b = account();
    create(
        b.user,
        "EXPENSE",
        "1",
        "식비",
        "2026-09-15",
        new Writes.Location(
            "MAP", null, null, "0", "179", null, null, false, null, null, null, null, null));
    create(
        b.user,
        "EXPENSE",
        "2",
        "식비",
        "2026-09-15",
        new Writes.Location(
            "MAP", null, null, "0", "-179", null, null, false, null, null, null, null, null));
    create(
        b.user,
        "EXPENSE",
        "4",
        "식비",
        "2026-09-15",
        new Writes.Location(
            "MAP", null, null, "0", "0", null, null, false, null, null, null, null, null));
    var q = bbox();
    q.set("south", "-10");
    q.set("north", "10");
    q.set("west", "170");
    q.set("east", "-170");
    assertEquals("3", queries.markers(b.user, q, false).get("viewportExpenseTotal"));
  }

  @Test
  void realHttpSignupLoginCsrfCrudLogout() throws Exception {
    var get =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
            "/api/v1/auth/csrf");
    var initial = mvc.perform(get).andReturn().getResponse();
    assertEquals(200, initial.getStatus());
    var csrf = initial.getCookie("XSRF-TOKEN");
    assertNotNull(csrf);
    String email = UUID.randomUUID() + "@example.invalid", password = Tokens.random() + "A1";
    var signup =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/auth/signup")
            .header("Origin", "https://example.invalid")
            .cookie(csrf)
            .header("X-XSRF-TOKEN", csrf.getValue())
            .contentType("application/json")
            .content(
                json.writeValueAsString(
                    new AuthService.Signup(email, password, password, true, "v1")));
    assertEquals(201, mvc.perform(signup).andReturn().getResponse().getStatus());
    var login =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/auth/login")
            .header("Origin", "https://example.invalid")
            .cookie(csrf)
            .header("X-XSRF-TOKEN", csrf.getValue())
            .contentType("application/json")
            .content(json.writeValueAsString(new AuthService.Login(email, password)));
    var response = mvc.perform(login).andReturn().getResponse();
    assertEquals(200, response.getStatus());
    var access = response.getCookie("mm_access");
    var refresh = response.getCookie("mm_refresh");
    assertNotNull(access);
    assertTrue(access.isHttpOnly());
    assertTrue(access.getSecure());
    csrf = mvc.perform(get).andReturn().getResponse().getCookie("XSRF-TOKEN");
    assertNotNull(csrf);
    var categories =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/categories?type=EXPENSE")
                    .cookie(access))
            .andReturn()
            .getResponse();
    assertEquals(200, categories.getStatus());
    UUID category =
        UUID.fromString(
            json.readTree(categories.getContentAsString())
                .path("data")
                .path("items")
                .get(0)
                .path("id")
                .asText());
    var body =
        new Writes.Transaction("EXPENSE", "12000", "KRW", category, "2026-09-15", null, null);
    var created =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/transactions")
                    .header("Origin", "https://example.invalid")
                    .cookie(access, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("application/json")
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertEquals(201, created.getStatus());
    assertEquals("\"0\"", created.getHeader("ETag"));
    assertEquals(
        "12000", json.readTree(created.getContentAsString()).path("data").path("amount").asText());
    var logout =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/auth/logout")
                    .header("Origin", "https://example.invalid")
                    .cookie(access, refresh, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType("application/json")
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertEquals(204, logout.getStatus());
    assertEquals(0, logout.getCookie("mm_access").getMaxAge());
    assertEquals(
        401,
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/transactions")
                    .cookie(access))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void categoryTypeAndOwnerAttacks() {
    var a = account();
    var b = account();
    UUID food = category(a.user, "EXPENSE", "식비"), salary = category(a.user, "INCOME", "급여");
    var wrong = new Writes.Transaction("EXPENSE", "1", "KRW", salary, "2026-09-15", null, null);
    assertEquals(
        400,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () -> ledger.create(a.user, UUID.randomUUID(), wrong))
            .status);
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            db.update(
                "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date)"
                    + " VALUES (?,?,'EXPENSE',1,?,DATE '2026-09-15')",
                UUID.randomUUID(),
                a.user,
                salary));
    UUID id = create(b.user, "EXPENSE", "1", "식비", "2026-09-15", search("11680"));
    var valid = new Writes.Transaction("EXPENSE", "1", "KRW", food, "2026-09-15", null, null);
    assertEquals(
        404,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () -> ledger.replace(a.user, id, null, valid))
            .status);
    assertEquals(
        404,
        assertThrows(com.moneymap.common.ApiException.class, () -> ledger.delete(a.user, id, null))
            .status);
    assertEquals(
        409,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () -> ledger.patchCategory(a.user, food, "\"0\"", Map.of("name", "수정 불가")))
            .status);
    var custom = ledger.createCategory(a.user, new Writes.Category("EXPENSE", "새 분류", null, null));
    assertEquals(
        409,
        assertThrows(
                com.moneymap.common.ApiException.class,
                () ->
                    ledger.createCategory(
                        a.user, new Writes.Category("EXPENSE", " 새 분류 ", null, null)))
            .status);
    assertEquals("1", ledger.get(b.user, id).get("amount"));
  }

  @Test
  void everyMapCapBoundaryAndInvalidLatitude() {
    for (int[] pair : new int[][] {{5, 500}, {7, 300}, {11, 200}}) {
      var a = account();
      UUID c = category(a.user, "EXPENSE", "식비");
      int cap = pair[1];
      db.update(
          "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT"
              + " gen_random_uuid(),?,'EXPENSE',1,?,DATE '2026-09-15' FROM generate_series(1,?)",
          a.user,
          c,
          cap);
      db.update(
          "INSERT INTO transaction_locations(transaction_id,user_id,source,latitude,longitude)"
              + " SELECT id,user_id,'MAP',37+row_number() OVER(ORDER BY id)*0.000001,127 FROM"
              + " transactions WHERE user_id=?",
          a.user);
      var q = bbox();
      q.set("level", Integer.toString(pair[0]));
      var exact = queries.markers(a.user, q, false);
      assertEquals(false, exact.get("requiresNarrowing"));
      assertEquals(cap, items(exact).size());
      create(
          a.user,
          "EXPENSE",
          "1",
          "식비",
          "2026-09-15",
          new Writes.Location(
              "MAP", null, null, "37.9", "127", null, null, false, null, null, null, null, null));
      var beyond = queries.markers(a.user, q, false);
      assertEquals(true, beyond.get("requiresNarrowing"));
      assertTrue(items(beyond).isEmpty());
      assertEquals(Integer.toString(cap + 1), beyond.get("viewportExpenseTotal"));
      q.set("south", "39");
      assertEquals(
          400,
          assertThrows(
                  com.moneymap.common.ApiException.class, () -> queries.markers(a.user, q, false))
              .status);
    }
  }

  @Test
  void emptyPeriodAndListQueryPlan() throws Exception {
    var a = account();
    var empty = queries.statistics(a.user, "summary", september());
    assertEquals("0", empty.get("incomeTotal"));
    assertEquals("0", empty.get("expenseTotal"));
    assertEquals("0", empty.get("balance"));
    UUID c = category(a.user, "EXPENSE", "식비");
    db.update(
        "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT"
            + " gen_random_uuid(),?,'EXPENSE',1,?,DATE '2026-09-15' FROM generate_series(1,10000)",
        a.user,
        c);
    db.execute("ANALYZE transactions");
    String plan =
        db.queryForObject(
            "EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) "
                + LedgerRepository.SELECT
                + " WHERE t.user_id=? AND t.transaction_date BETWEEN DATE '2026-09-01' AND DATE"
                + " '2026-09-30' ORDER BY t.transaction_date DESC,t.created_at DESC,t.id DESC LIMIT"
                + " 21",
            String.class,
            a.user);
    var tree = json.readTree(plan).get(0);
    assertEquals(21, tree.path("Plan").path("Actual Rows").asInt());
    var scans = new ArrayList<String>();
    collectPlan(tree.path("Plan"), scans);
    System.out.println(
        "QUERY_PLAN rows=10000 returned=21 nodes="
            + String.join(",", scans)
            + " executionMs="
            + tree.path("Execution Time").asText());
  }

  private void collectPlan(com.fasterxml.jackson.databind.JsonNode node, List<String> scans) {
    scans.add(node.path("Node Type").asText() + ":" + node.path("Index Name").asText());
    for (var child : node.path("Plans")) collectPlan(child, scans);
  }
}
