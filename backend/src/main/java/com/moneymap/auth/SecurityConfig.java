package com.moneymap.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.*;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  public static String cookie(HttpServletRequest r, String name) {
    if (r.getCookies() != null)
      for (Cookie c : r.getCookies()) if (c.getName().equals(name)) return c.getValue();
    return null;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity h, Secrets s, AuthService auth, ObjectMapper mapper,
      RecoveryGate recovery)
      throws Exception {
    CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repo.setCookiePath("/");
    repo.setCookieCustomizer(b -> b.secure(s.secure).sameSite("Lax"));
    h.csrf(
        c ->
            c.csrfTokenRepository(repo)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    h.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable());
    h.cors(
        c ->
            c.configurationSource(
                r -> {
                  CorsConfiguration cfg = new CorsConfiguration();
                  cfg.setAllowedOrigins(List.of(s.origin));
                  cfg.setAllowCredentials(true);
                  cfg.setAllowedMethods(
                      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                  cfg.setAllowedHeaders(
                      List.of("Content-Type", "X-XSRF-TOKEN", "If-Match", "Idempotency-Key"));
                  cfg.setExposedHeaders(List.of("ETag", "X-Request-Id", "Idempotent-Replay"));
                  return cfg;
                }));
    h.authorizeHttpRequests(
        c ->
            c.requestMatchers(
                    "/api/v1/auth/**", "/actuator/health/liveness", "/actuator/health/readiness")
                .permitAll()
                .anyRequest()
                .authenticated());
    h.exceptionHandling(
        c ->
            c.authenticationEntryPoint((r, w, e) -> write(mapper, r, w, 401, "UNAUTHENTICATED"))
                .accessDeniedHandler((r, w, e) -> write(mapper, r, w, 403, "CSRF_INVALID")));
    h.addFilterBefore(
        new RequestFilter(s, mapper, recovery::allows), org.springframework.web.filter.CorsFilter.class);
    h.addFilterAfter(
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest r, HttpServletResponse w, FilterChain chain)
              throws ServletException, IOException {
            if (!r.getRequestURI().startsWith("/api/v1/auth/")
                && !r.getRequestURI().startsWith("/actuator/health/")) {
              try {
                if (cookie(r, "mm_access") == null && cookie(r, "mm_refresh") != null)
                  throw new ApiException(401, "ACCESS_EXPIRED");
                var id = auth.authenticate(cookie(r, "mm_access"));
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(id, null, List.of()));
              } catch (ApiException e) {
                write(mapper, r, w, e.status, e.code);
                return;
              } catch (org.springframework.dao.DataAccessException e) {
                write(mapper, r, w, 503, "SERVICE_UNAVAILABLE");
                return;
              }
            }
            chain.doFilter(r, w);
          }
        },
        CsrfFilter.class);
    return h.build();
  }

  private static void write(
      ObjectMapper m, HttpServletRequest r, HttpServletResponse w, int status, String code)
      throws IOException {
    r.setAttribute("errorCode", code);
    w.setStatus(status);
    w.setContentType("application/json");
    m.writeValue(w.getOutputStream(), Responses.error(code, r));
  }
}
