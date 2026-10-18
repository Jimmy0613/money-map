package com.moneymap;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymap.auth.*;
import com.moneymap.common.*;
import com.moneymap.ledger.*;
import jakarta.servlet.http.Cookie;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {AuthController.class, LedgerController.class, MapDiagnostics.class},
    excludeAutoConfiguration =
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class)
@Import({SecurityConfig.class, Secrets.class})
class HttpContractTest {
  // Business contract tests isolate the recovery authority; RecoveryAuthorityIntegrationTest
  // exercises the actual gate, signed HTTP protocol, and PostgreSQL binding separately.
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.moneymap.common.RecoveryGate recoveryGate;
  @org.junit.jupiter.api.BeforeEach
  void permitBusinessFixture() {
    org.mockito.Mockito.when(recoveryGate.allows()).thenReturn(true);
  }

  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean LedgerService ledger;
  @MockitoBean Queries queries;
  @MockitoBean io.micrometer.core.instrument.MeterRegistry meters;
  @MockitoBean RateLimits rateLimits;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    p.add(
        "app.cursor-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    p.add("app.origin", () -> "https://example.invalid");
    p.add("app.cookie-secure", () -> true);
  }

  @BeforeEach
  void unauthenticated() {
    when(auth.authenticate(null)).thenThrow(new ApiException(401, "UNAUTHENTICATED"));
  }

  @Test
  void recoveryBlocksAuthenticationAndReadinessBeforeOtherSecurityFilters() throws Exception {
    when(recoveryGate.allows()).thenReturn(false);
    for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/refresh",
        "/api/v1/transactions", "/actuator/health/readiness")) {
      mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error.code").value("RECOVERY_REQUIRED"));
    }
    verifyNoInteractions(auth);
  }

  @Test
  void diagnosticsRejectUnknownCodesPrivateFieldsAndUnauthenticatedRequests() throws Exception {
    mvc.perform(post("/api/v1/diagnostics/map-errors").with(csrf()).header("Origin", "https://example.invalid").contentType("application/json").content("{\"code\":\"SEARCH_FAILED\"}"))
        .andExpect(status().isUnauthorized());
    when(auth.authenticate("synthetic-cookie")).thenReturn(new Tokens.Identity(UUID.randomUUID(), UUID.randomUUID()));
    for (String body : List.of("{\"code\":\"PRIVATE_VALUE\"}", "{\"code\":\"SEARCH_FAILED\",\"query\":\"private\"}", "{\"code\":null}")) {
      mvc.perform(post("/api/v1/diagnostics/map-errors").with(csrf()).cookie(new Cookie("mm_access", "synthetic-cookie")).header("Origin", "https://example.invalid").contentType("application/json").content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(meters);
  }

  @Test
  void protectedUnauthenticatedReturnsSafeEnvelope() throws Exception {
    mvc.perform(get("/api/v1/transactions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void expiredAccessCookieCanTriggerOneRefresh() throws Exception {
    mvc.perform(get("/api/v1/transactions").cookie(new Cookie("mm_refresh", "synthetic-refresh")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("ACCESS_EXPIRED"));
  }

  @Test
  void mutationRequiresCsrf() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    verifyNoInteractions(ledger);
  }

  @Test
  void wrongOriginRejected() throws Exception {
    mvc.perform(
            post("/api/v1/auth/logout")
                .with(csrf())
                .header("Origin", "https://attacker.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ORIGIN_DENIED"))
        .andExpect(header().exists("X-Request-Id"));
    verify(auth, never()).logout(any(), any());
  }

  @Test
  void oversizedBodyRejected() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content("x".repeat(16385)))
        .andExpect(status().isPayloadTooLarge());
  }

  @Test
  void unknownJsonPropertyRejected() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content(
                    "{\"email\":\"a@example.invalid\",\"password\":\"Fixture12345\",\"unexpected\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void otherUsersObjectStays404() throws Exception {
    UUID user = UUID.randomUUID(), id = UUID.randomUUID();
    when(auth.authenticate("synthetic-cookie"))
        .thenReturn(new Tokens.Identity(user, UUID.randomUUID()));
    when(ledger.get(user, id)).thenThrow(ApiException.missing());
    mvc.perform(
            get("/api/v1/transactions/" + id).cookie(new Cookie("mm_access", "synthetic-cookie")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
  }

  @Test
  void logoutExpiresCookiesWithoutTokenResponse() throws Exception {
    mvc.perform(
            post("/api/v1/auth/logout")
                .with(csrf())
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""))
        .andExpect(cookie().maxAge("mm_access", 0))
        .andExpect(cookie().httpOnly("mm_access", true))
        .andExpect(cookie().secure("mm_access", true));
  }

  @Test
  void invalidFieldsHaveSafeMetadataWithoutRejectedValues() throws Exception {
    mvc.perform(
            post("/api/v1/auth/signup")
                .with(csrf())
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content(
                    "{\"email\":\"not-an-email-sensitive-marker\",\"password\":\"short\",\"passwordConfirmation\":\"short\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.fields[0].field").value("email"))
        .andExpect(jsonPath("$.error.fields[0].code").value("INVALID_VALUE"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-marker"))))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
    verify(auth, never()).signup(any(), any());
  }

  @Test
  void serviceFieldErrorPreservesEnvelope() throws Exception {
    when(auth.signup(any(), any()))
        .thenThrow(ApiException.invalid("passwordConfirmation", "MISMATCH", "비밀번호가 일치하지 않습니다."));
    mvc.perform(
            post("/api/v1/auth/signup")
                .with(csrf())
                .header("Origin", "https://example.invalid")
                .contentType("application/json")
                .content(
                    "{\"email\":\"fixture@example.invalid\",\"password\":\"Synthetic123\",\"passwordConfirmation\":\"Mismatch123\",\"termsAccepted\":true,\"termsVersion\":\"v1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.error.fields[0].field").value("passwordConfirmation"))
        .andExpect(jsonPath("$.error.fields[0].code").value("MISMATCH"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }
}
