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

class PersonalAccountAccessTest {
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final Tokens tokens = mock(Tokens.class);
  private final AuthService service = new AuthService(db, tokens, new RateLimits(),
      new AccountAccessPolicy(new MockEnvironment()
          .withProperty("app.access.restricted", "true")
          .withProperty("app.access.allowed-emails", "owner@example.test")));

  @Test void rejectedSignupDoesNotWriteAnyUserOrCategory() {
    var error = assertThrows(ApiException.class, () -> service.signup(new AuthService.Signup(
        "other@example.test", "Password1234", "Password1234", true, "v1"), "127.0.0.1"));
    assertEquals(403, error.status);
    assertEquals("REGISTRATION_CLOSED", error.code);
    verifyNoInteractions(db);
  }

  @Test void removingAnEmailAlsoRejectsAnExistingRefreshSessionWithoutIssuingTokens() {
    when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
        "id", UUID.randomUUID(), "user_id", UUID.randomUUID(),
        "email", "removed@example.test", "expires_at", Timestamp.from(Instant.now().plusSeconds(3600)))));
    var error = assertThrows(ApiException.class, () -> service.refresh("synthetic-refresh"));
    assertEquals(401, error.status);
    verifyNoInteractions(tokens);
    verify(db, never()).update(anyString(), any(Object[].class));
  }
}
