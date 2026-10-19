package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.auth.*;
import com.moneymap.common.*;
import com.moneymap.ledger.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class DemoProtectionTest {
  private final AccountAccessPolicy policy = new AccountAccessPolicy(new MockEnvironment()
      .withProperty("app.demo.emails", "DEMO@example.invalid"));

  @Test void demoCannotDeleteAccountEvenWithPublicPassword() {
    var db = mock(JdbcTemplate.class);
    UUID user = UUID.randomUUID();
    when(db.queryForList("SELECT email,password_hash FROM users WHERE id=? FOR UPDATE", user))
        .thenReturn(List.of(Map.of("email", "demo@example.invalid", "password_hash", "unused")));
    var auth = new AuthService(db, mock(Tokens.class), new RateLimits(), policy);
    var e = assertThrows(ApiException.class,
        () -> auth.delete(user, new AuthService.DeleteAccount("PublicPassword123", "DELETE")));
    assertEquals(403, e.status);
    assertEquals("DEMO_PROTECTED", e.code);
    verify(db, never()).update(anyString(), any(Object[].class));
    assertFalse(policy.isDemo("owner@example.invalid"));
  }

  @Test void creationCapPersistsAcrossNewServiceInstancesAndDoesNotBlockReplay() throws Exception {
    var repo = mock(LedgerRepository.class);
    var json = new ObjectMapper();
    UUID user = UUID.randomUUID(), key = UUID.randomUUID(), id = UUID.randomUUID();
    var input = new Writes.Transaction("EXPENSE", "1000", "KRW", UUID.randomUUID(), "2026-09-01", null, null);
    when(repo.user(user, true)).thenReturn(Map.of("email", "demo@example.invalid", "timezone", "Asia/Seoul"));
    when(repo.query(anyString(), any(Object[].class))).thenAnswer(call ->
        ((String) call.getArgument(0)).startsWith("SELECT count")
            ? List.of(Map.of("count", 200L)) : List.of());
    for (int i = 0; i < 2; i++) {
      var service = new LedgerService(repo, json, policy, new RateLimits());
      assertEquals("DEMO_QUOTA_EXCEEDED",
          assertThrows(ApiException.class, () -> service.create(user, key, input)).code);
    }
    verify(repo, never()).update(anyString(), any(Object[].class));
    when(repo.query("SELECT body_hash,transaction_id FROM operation_keys WHERE user_id=? AND request_key=?", user, key))
        .thenReturn(List.of(Map.of("body_hash", Tokens.hash(json.writeValueAsString(input)), "transaction_id", id)));
    var replayService = spy(new LedgerService(repo, json, policy, new RateLimits()));
    doReturn(Map.of("id", id)).when(replayService).get(user, id);
    assertTrue(replayService.create(user, key, input).replay());
  }

  @Test void archivedCategoriesStillCountTowardDemoLimit() {
    var repo = mock(LedgerRepository.class);
    UUID user = UUID.randomUUID();
    when(repo.user(user, true)).thenReturn(Map.of("email", "demo@example.invalid"));
    when(repo.query("SELECT count(*) AS count FROM categories WHERE user_id=?", user))
        .thenReturn(List.of(Map.of("count", 40L)));
    var service = new LedgerService(repo, new ObjectMapper(), policy, new RateLimits());
    assertEquals("DEMO_QUOTA_EXCEEDED", assertThrows(ApiException.class,
        () -> service.createCategory(user, new Writes.Category("EXPENSE", "Sample", null, null))).code);
    verify(repo, never()).update(anyString(), any(Object[].class));
  }
}
