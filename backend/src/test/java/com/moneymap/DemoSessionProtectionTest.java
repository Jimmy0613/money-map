package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moneymap.auth.*;
import com.moneymap.common.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class DemoSessionProtectionTest {
  private static final String DEMO = "demo@example.invalid";
  private static final String OWNER = "owner@example.invalid";
  private static final String PASSWORD = "FixturePassword123";
  private final UUID user = UUID.randomUUID(), session = UUID.randomUUID();
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final Tokens tokens = mock(Tokens.class);
  private final AccountAccessPolicy policy = new AccountAccessPolicy(new MockEnvironment()
      .withProperty("app.demo.emails", DEMO));
  private final AuthService auth = new AuthService(db, tokens, new RateLimits(), policy);

  private void loginFixture(String email) {
    String hash = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(PASSWORD);
    when(db.queryForList("SELECT id,password_hash FROM users WHERE email=? FOR UPDATE", email))
        .thenReturn(List.of(Map.of("id", user, "password_hash", hash)));
  }

  private void refreshFixture(String email, boolean consumed) {
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
        "id", session, "user_id", user, "email", email,
        "expires_at", Timestamp.from(Instant.now().plusSeconds(3600)))));
    when(db.queryForMap("SELECT consumed_at FROM refresh_tokens WHERE token_hash=?", Tokens.hash("fixture-refresh")))
        .thenReturn(consumed ? Map.of("consumed_at", Timestamp.from(Instant.now())) : Map.of());
  }

  @Test void demoLoginPrunesOnlyItsOwnSessionsBeforeIssuingNewOne() {
    loginFixture(DEMO);
    auth.login(new AuthService.Login(DEMO, PASSWORD), "192.0.2.1");
    var order = inOrder(db);
    order.verify(db).queryForList("SELECT id,password_hash FROM users WHERE email=? FOR UPDATE", DEMO);
    order.verify(db).update("DELETE FROM auth_sessions WHERE user_id=? AND (revoked_at IS NOT NULL OR expires_at<=now())", user);
    order.verify(db).update("DELETE FROM auth_sessions WHERE user_id=? AND id IN (SELECT id FROM auth_sessions"
        + " WHERE user_id=? ORDER BY created_at DESC,id DESC OFFSET ?)", user, user, 9);
    order.verify(db).update(eq("INSERT INTO auth_sessions(id,user_id,expires_at) VALUES (?,?,now()+interval '14 days')"), any(UUID.class), eq(user));
  }

  @Test void ordinaryLoginDoesNotPruneSessions() {
    loginFixture(OWNER);
    auth.login(new AuthService.Login(OWNER, PASSWORD), "192.0.2.1");
    verify(db, never()).update(startsWith("DELETE FROM auth_sessions"), any(Object[].class));
  }

  @Test void changingIpCannotBypassDemoAccountLoginBudget() {
    loginFixture(DEMO);
    for (int i = 0; i < 10; i++) auth.login(new AuthService.Login(DEMO, PASSWORD), "192.0.2." + i);
    var error = assertThrows(ApiException.class,
        () -> auth.login(new AuthService.Login(DEMO, PASSWORD), "192.0.2.99"));
    assertEquals(429, error.status);
    verify(db, times(10)).queryForList("SELECT id,password_hash FROM users WHERE email=? FOR UPDATE", DEMO);
  }

  @Test void demoTokenCapRevokesSessionWithoutCreatingMoreTokens() {
    refreshFixture(DEMO, false);
    when(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id=?", Integer.class, session)).thenReturn(60);
    var error = assertThrows(ApiException.class, () -> auth.refresh("fixture-refresh"));
    assertEquals("DEMO_SESSION_EXPIRED", error.code);
    assertEquals(401, error.status);
    verify(db).update("UPDATE auth_sessions SET revoked_at=now() WHERE id=?", session);
    verify(db, never()).update(startsWith("INSERT INTO refresh_tokens"), any(Object[].class));
    verifyNoInteractions(tokens);
  }

  @Test void demoCanRefreshUntilItsTokenCapAndOrdinaryAccountsDoNotUseIt() {
    refreshFixture(DEMO, false);
    when(db.queryForObject("SELECT count(*) FROM refresh_tokens WHERE session_id=?", Integer.class, session)).thenReturn(59);
    assertEquals(user, auth.refresh("fixture-refresh").user());
    verify(db).update("UPDATE refresh_tokens SET consumed_at=now() WHERE token_hash=?", Tokens.hash("fixture-refresh"));
    verify(db).update(eq("INSERT INTO refresh_tokens(id,session_id,token_hash) VALUES (?,?,?)"), any(UUID.class), eq(session), anyString());
    clearInvocations(db, tokens);
    refreshFixture(OWNER, false);
    assertEquals(user, auth.refresh("fixture-refresh").user());
    verify(db, never()).queryForObject(anyString(), eq(Integer.class), any(UUID.class));
  }

  @Test void consumedDemoTokenStillTriggersReuseProtectionBeforeQuota() {
    refreshFixture(DEMO, true);
    assertEquals("REFRESH_REUSED", assertThrows(ApiException.class, () -> auth.refresh("fixture-refresh")).code);
    verify(db).update("UPDATE auth_sessions SET revoked_at=now() WHERE id=?", session);
    verify(db, never()).queryForObject(anyString(), eq(Integer.class), any(UUID.class));
    verifyNoInteractions(tokens);
  }

  @Test void invalidSessionBudgetsFailAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> new AccountAccessPolicy(new MockEnvironment()
        .withProperty("app.demo.max-sessions", "0")));
    assertThrows(IllegalArgumentException.class, () -> new AccountAccessPolicy(new MockEnvironment()
        .withProperty("app.demo.max-session-tokens", "0")));
  }
}
