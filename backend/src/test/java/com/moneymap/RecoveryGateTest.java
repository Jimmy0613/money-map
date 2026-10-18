package com.moneymap;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class RecoveryGateTest {
  @Test void renderCommitMustMatchRegisteredDeploymentBeforeAnyDatabaseRead() throws Exception {
    var env = new MockEnvironment().withProperty("RENDER_GIT_COMMIT", "new-commit")
        .withProperty("app.recovery.deployment", "registered-commit")
        .withProperty("app.recovery.environment", "production")
        .withProperty("app.recovery.authority-url", "https://example.invalid/decision")
        .withProperty("app.recovery.public-key", Base64.getEncoder().encodeToString(
            java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()));
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
    var secrets = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "https://example.invalid", true, env);
    var db = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    assertFalse(new RecoveryGate(secrets, db, new ObjectMapper(), env).allows());
    org.mockito.Mockito.verifyNoInteractions(db);
  }
  @Test void productionDefaultsClosedEvenWithExplicitFalse() throws Exception {
    for (String flag : List.of("false", "")) {
      var env = new MockEnvironment();
      if (!flag.isEmpty()) env.withProperty("app.recovery-required", flag);
      byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
      var secrets = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
          "https://example.invalid", true, env);
      var gate = new RecoveryGate(secrets, org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
          new ObjectMapper(), env);
      assertFalse(gate.allows());
      var response = new MockHttpServletResponse();
      new RequestFilter(secrets, new ObjectMapper(), gate::allows).doFilter(
          new MockHttpServletRequest("GET", "/actuator/health/readiness"), response,
          (r,w) -> fail("Unregistered production must be blocked"));
      assertEquals(503, response.getStatus());
    }
  }

  @Test void localExceptionCannotUsePublicOrigin() {
    var env = new MockEnvironment(); env.setActiveProfiles("local");
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
    assertThrows(IllegalStateException.class, () -> new Secrets(Base64.getEncoder().encodeToString(a),
        Base64.getEncoder().encodeToString(b), "https://example.invalid", true, env));
    var local = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "http://localhost:3000", false, env);
    assertTrue(new RecoveryGate(local, null, new ObjectMapper(), env).allows());
    env.withProperty("app.recovery-required", "true");
    local = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "http://localhost:3000", false, env);
    assertFalse(new RecoveryGate(local, null, new ObjectMapper(), env).allows());
  }

  @Test void localProfileRejectsDirectNonLoopbackRequest() throws Exception {
    var env = new MockEnvironment(); env.setActiveProfiles("local");
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
    var secrets = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "http://localhost:3000", false, env);
    var filter = new RequestFilter(secrets, new ObjectMapper());
    var local = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
    local.setRemoteAddr("127.0.0.1");
    var response = new MockHttpServletResponse();
    filter.doFilter(local, response, (r,w) -> w.getWriter().write("local"));
    assertEquals("local", response.getContentAsString());
    var remote = new MockHttpServletRequest("GET", "/api/v1/auth/csrf");
    remote.setRemoteAddr("192.0.2.1");
    response = new MockHttpServletResponse();
    filter.doFilter(remote, response, (r,w) -> fail("Remote local-profile access"));
    assertEquals(503, response.getStatus());
  }

  @Test void recoveryBlocksLoginRefreshPrivateReadsAndReadinessButAllowsLiveness() throws Exception {
    byte[] a = new byte[32], b = new byte[32];
    Arrays.fill(b, (byte) 1);
    var secrets = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "https://example.invalid", true, new MockEnvironment().withProperty("app.recovery-required", "true"));
    var filter = new RequestFilter(secrets, new ObjectMapper());
    for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/transactions", "/actuator/health/readiness")) {
      var request = new MockHttpServletRequest("GET", path);
      var response = new MockHttpServletResponse();
      filter.doFilter(request, response, (r,w) -> fail("Recovery must block before authentication"));
      assertEquals(503, response.getStatus());
      assertTrue(response.getContentAsString().contains("RECOVERY_REQUIRED"));
    }
    var response = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness"), response, (r,w) -> w.getWriter().write("alive"));
    assertEquals("alive", response.getContentAsString());
  }
}
