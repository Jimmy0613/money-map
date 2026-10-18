package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.auth.*;
import com.moneymap.common.*;
import com.moneymap.ledger.Cursors;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class SecurityUnitTest {
  private Secrets secrets() {
    byte[] a = new byte[32], b = new byte[32];
    new java.security.SecureRandom().nextBytes(a);
    new java.security.SecureRandom().nextBytes(b);
    return new Secrets(
        Base64.getEncoder().encodeToString(a),
        Base64.getEncoder().encodeToString(b),
        "http://localhost:3000",
        false,
        new MockEnvironment().withProperty("spring.profiles.active", "local"));
  }

  @Test
  void tokensVerifyIssuerAndSignature() {
    var tokens = new Tokens(secrets());
    var user = UUID.randomUUID();
    var sid = UUID.randomUUID();
    String token = tokens.access(user, sid);
    assertEquals(new Tokens.Identity(user, sid), tokens.verify(token, false));
    assertThrows(ApiException.class, () -> new Tokens(secrets()).verify(token, false));
    assertThrows(ApiException.class, () -> tokens.verify("invalid", false));
  }

  @Test
  void cursorBoundToUserFilterAndRevision() {
    var c = new Cursors(secrets(), new ObjectMapper());
    UUID u = UUID.randomUUID();
    String token = c.encode(u, "5", "filter", "tuple");
    assertEquals("tuple", c.decode(token, u, "5", "filter"));
    assertThrows(ApiException.class, () -> c.decode(token, UUID.randomUUID(), "5", "filter"));
    assertThrows(ApiException.class, () -> c.decode(token, u, "5", "other"));
    assertEquals(
        "CURSOR_STALE",
        assertThrows(ApiException.class, () -> c.decode(token, u, "6", "filter")).code);
    assertThrows(ApiException.class, () -> c.decode(token + "x", u, "5", "filter"));
  }

  @Test
  void secureCookieCannotBeDisabledOutsideLocalhost() {
    byte[] a = new byte[32], b = new byte[32];
    Arrays.fill(b, (byte) 1);
    assertThrows(
        IllegalStateException.class,
        () ->
            new Secrets(
                Base64.getEncoder().encodeToString(a),
                Base64.getEncoder().encodeToString(b),
                "https://example.invalid",
                false,
                new MockEnvironment()));
  }

  @Test
  void rateLimitBoundsAttempts() {
    var r = new RateLimits();
    r.hit("fixture", 2, 60);
    r.hit("fixture", 2, 60);
    assertEquals(429, assertThrows(ApiException.class, () -> r.hit("fixture", 2, 60)).status);
    r.clear("fixture");
    assertDoesNotThrow(() -> r.hit("fixture", 2, 60));
  }
}
