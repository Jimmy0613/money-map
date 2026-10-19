package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.moneymap.auth.*;
import com.moneymap.common.ApiException;
import com.moneymap.ledger.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
@Tag("integration")
class DemoPostgresIntegrationTest {
  @Container static TestPostgres postgres = new TestPostgres();
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.origin", () -> "https://example.invalid");
    r.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add("app.cursor-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add("app.demo.emails", () -> "session@example.invalid,quota@example.invalid");
    r.add("app.demo.max-sessions", () -> 2);
    r.add("app.demo.max-session-tokens", () -> 2);
    r.add("app.demo.max-created-transactions", () -> 2);
    r.add("app.demo.max-categories", () -> 16);
  }
  @Autowired AuthService auth;
  @Autowired LedgerService ledger;
  @Autowired JdbcTemplate db;
  private static final String PASSWORD = "SyntheticPassword123";
  private UUID signup(String email) {
    return (UUID) auth.signup(new AuthService.Signup(email, PASSWORD, PASSWORD, true, "v1"), email).get("id");
  }
  private AuthService.Credentials login(String email) {
    return auth.login(new AuthService.Login(email, PASSWORD), email);
  }
  private int count(String table, String column, UUID id) {
    return db.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
  }

  @Test void sessionsCascadeAndRefreshRevocationPersistWhileOwnerIsUnaffected() throws Exception {
    String email = "session@example.invalid";
    UUID user = signup(email), owner = signup("owner@example.invalid");
    var first = login(email);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> login(email));
      var b = executor.submit(() -> login(email));
      a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
    }
    assertEquals(2, count("auth_sessions", "user_id", user));
    assertEquals(0, count("refresh_tokens", "session_id", first.session()));
    var current = login(email);
    var rotated = auth.refresh(current.refresh());
    assertEquals("DEMO_SESSION_EXPIRED", assertThrows(ApiException.class,
        () -> auth.refresh(rotated.refresh())).code);
    assertNotNull(db.queryForObject("SELECT revoked_at FROM auth_sessions WHERE id=?", Object.class, current.session()));
    assertEquals(2, count("refresh_tokens", "session_id", current.session()));
    for (int i = 0; i < 3; i++) login("owner@example.invalid");
    assertEquals(3, count("auth_sessions", "user_id", owner));
    assertEquals("DEMO_PROTECTED", assertThrows(ApiException.class,
        () -> auth.delete(user, new AuthService.DeleteAccount(PASSWORD, "DELETE"))).code);
    assertEquals(1, db.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, user));
  }

  @Test void concurrentCreatesRespectPersistentCapAndReplayWorksAtCap() throws Exception {
    UUID user = signup("quota@example.invalid");
    UUID category = db.queryForObject("SELECT id FROM categories WHERE user_id=? AND type='EXPENSE' LIMIT 1", UUID.class, user);
    var body = new Writes.Transaction("EXPENSE", "1000", "KRW", category, LocalDate.now().minusDays(1).toString(), "synthetic", null);
    UUID key = UUID.randomUUID();
    var first = ledger.create(user, key, body);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    Callable<String> create = () -> {
      ready.countDown(); start.await();
      try { ledger.create(user, UUID.randomUUID(), body); return "CREATED"; }
      catch (ApiException e) { return e.code; }
    };
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(create); var b = executor.submit(create);
      assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
      assertEquals(Set.of("CREATED", "DEMO_QUOTA_EXCEEDED"), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
    }
    assertEquals(2, count("operation_keys", "user_id", user));
    assertTrue(ledger.create(user, key, body).replay());
    UUID id = (UUID) first.transaction().get("id");
    ledger.delete(user, id, "\"" + first.transaction().get("version") + "\"");
    assertEquals(2, count("operation_keys", "user_id", user));
    assertEquals("DEMO_QUOTA_EXCEEDED", assertThrows(ApiException.class,
        () -> ledger.create(user, UUID.randomUUID(), body)).code);
    assertEquals("DEMO_QUOTA_EXCEEDED", assertThrows(ApiException.class,
        () -> ledger.createCategory(user, new Writes.Category("EXPENSE", "Sample", null, null))).code);
  }
}
