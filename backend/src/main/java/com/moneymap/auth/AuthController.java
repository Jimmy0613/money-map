package com.moneymap.auth;

import com.moneymap.common.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final AuthService service;
  private final Secrets secrets;

  public AuthController(AuthService service, Secrets secrets) {
    this.service = service;
    this.secrets = secrets;
  }

  @GetMapping("/auth/csrf")
  public Object csrf(CsrfToken token, HttpServletRequest r) {
    token.getToken();
    return Responses.data(Map.of("headerName", token.getHeaderName()), r);
  }

  @PostMapping("/auth/signup")
  public ResponseEntity<?> signup(@Valid @RequestBody AuthService.Signup d, HttpServletRequest r) {
    return ResponseEntity.status(201).body(Responses.data(service.signup(d, r.getRemoteAddr()), r));
  }

  @PostMapping("/auth/login")
  public Object login(
      @Valid @RequestBody AuthService.Login d, HttpServletRequest r, HttpServletResponse w) {
    var c = service.login(d, r.getRemoteAddr());
    cookies(w, c);
    var me = service.me(c.user());
    return Responses.data(
        Map.of("userId", c.user(), "timezone", me.get("timezone"), "today", me.get("today")), r);
  }

  @PostMapping("/auth/refresh")
  public Object refresh(
      @RequestBody Map<String, Object> body, HttpServletRequest r, HttpServletResponse w) {
    empty(body);
    cookies(w, service.refresh(SecurityConfig.cookie(r, "mm_refresh")));
    return Responses.data(Map.of("refreshed", true), r);
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<?> logout(
      @RequestBody Map<String, Object> body, HttpServletRequest r, HttpServletResponse w) {
    empty(body);
    service.logout(SecurityConfig.cookie(r, "mm_access"), SecurityConfig.cookie(r, "mm_refresh"));
    clear(w);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users/me")
  public ResponseEntity<?> me(@AuthenticationPrincipal Tokens.Identity id, HttpServletRequest r) {
    var u = service.me(id.user());
    return ResponseEntity.ok().eTag(u.get("version").toString()).body(Responses.data(u, r));
  }

  @DeleteMapping("/users/me")
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal Tokens.Identity id,
      @Valid @RequestBody AuthService.DeleteAccount d,
      HttpServletResponse w) {
    service.delete(id.user(), d);
    clear(w);
    return ResponseEntity.noContent().build();
  }

  private void empty(Map<String, Object> b) {
    if (!b.isEmpty()) throw ApiException.invalid();
  }

  private void cookies(HttpServletResponse w, AuthService.Credentials c) {
    clearCsrf(w);
    set(w, "mm_access", c.access(), 300);
    set(w, "mm_refresh", c.refresh(), 1209600);
  }

  private void clearCsrf(HttpServletResponse w) {
    w.addHeader(
        "Set-Cookie",
        ResponseCookie.from("XSRF-TOKEN", "")
            .secure(secrets.secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
  }

  private void clear(HttpServletResponse w) {
    clearCsrf(w);
    set(w, "mm_access", "", 0);
    set(w, "mm_refresh", "", 0);
  }

  private void set(HttpServletResponse w, String name, String value, long age) {
    w.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(secrets.secure)
            .sameSite("Lax")
            .path("/api/v1")
            .maxAge(age)
            .build()
            .toString());
  }
}
