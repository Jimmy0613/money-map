package com.moneymap.common;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class RequestAdmissionTest {
  private Secrets secrets() {
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
    return new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "https://example.invalid", true, new MockEnvironment());
  }

  @Test void writeBudgetDoesNotBlockReadsAndResetsUsingMonotonicTime() {
    var now = new AtomicLong();
    var budget = new RequestAdmission(3, 1, now::get);
    budget.check(true);
    assertEquals(429, assertThrows(ApiException.class, () -> budget.check(true)).status);
    budget.check(false); budget.check(false);
    assertThrows(ApiException.class, () -> budget.check(false));
    now.set(TimeUnit.MINUTES.toNanos(1));
    budget.check(true);
  }

  @Test void rejectedOriginAndKnownOrChunkedOversizedBodiesNeverReachAuthority() throws Exception {
    var calls = new AtomicInteger();
    var filter = new RequestFilter(secrets(), new ObjectMapper(), () -> { calls.incrementAndGet(); return true; });
    var wrong = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    wrong.addHeader("Origin", "https://attacker.invalid");
    var response = new MockHttpServletResponse();
    filter.doFilter(wrong, response, (r,w) -> fail("Invalid origin passed"));
    assertEquals(403, response.getStatus());
    for (boolean chunked : List.of(false, true)) {
      var request = new MockHttpServletRequest("POST", "/api/v1/auth/signup") {
        @Override public long getContentLengthLong() { return chunked ? -1 : super.getContentLengthLong(); }
      };
      request.addHeader("Origin", "https://example.invalid");
      request.setContent(new byte[16385]);
      response = new MockHttpServletResponse();
      filter.doFilter(request, response, (r,w) -> fail("Oversized body passed"));
      assertEquals(413, response.getStatus());
    }
    assertEquals(0, calls.get());
  }

  @Test void forwardedHeadersCannotBypassBudgetAndLivenessRemainsAvailable() throws Exception {
    var calls = new AtomicInteger();
    var filter = new RequestFilter(secrets(), new ObjectMapper(),
        () -> { calls.incrementAndGet(); return true; }, new RequestAdmission(1, 1));
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/csrf"),
        new MockHttpServletResponse(), (r,w) -> {});
    var request = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
    request.addHeader("X-Forwarded-For", "192.0.2.10");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (r,w) -> fail("Exhausted budget passed"));
    assertEquals(429, response.getStatus());
    assertEquals("60", response.getHeader("Retry-After"));
    assertEquals(1, calls.get());
    response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness"), response,
        (r,w) -> w.getWriter().write("alive"));
    assertEquals("alive", response.getContentAsString());
  }
}
