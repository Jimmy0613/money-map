package com.moneymap.ledger;

import com.moneymap.auth.Tokens;
import com.moneymap.common.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LedgerController {
  private final LedgerService service;
  private final Queries queries;

  public LedgerController(LedgerService s, Queries q) {
    service = s;
    queries = q;
  }

  @PostMapping("/transactions")
  public ResponseEntity<?> create(
      @AuthenticationPrincipal Tokens.Identity user,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody Writes.Transaction body,
      HttpServletRequest r) {
    var result = service.create(user.user(), Rules.uuid(key), body);
    return ResponseEntity.status(result.replay() ? 200 : 201)
        .header("Idempotent-Replay", Boolean.toString(result.replay()))
        .header("Location", "/api/v1/transactions/" + result.transaction().get("id"))
        .eTag(result.transaction().get("version").toString())
        .body(Responses.data(result.transaction(), r));
  }

  @GetMapping("/transactions/{id}")
  public ResponseEntity<?> get(
      @AuthenticationPrincipal Tokens.Identity user, @PathVariable UUID id, HttpServletRequest r) {
    return single(service.get(user.user(), id), r);
  }

  @PutMapping("/transactions/{id}")
  public ResponseEntity<?> replace(
      @AuthenticationPrincipal Tokens.Identity user,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match,
      @Valid @RequestBody Writes.Transaction body,
      HttpServletRequest r) {
    return single(service.replace(user.user(), id, match, body), r);
  }

  @DeleteMapping("/transactions/{id}")
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal Tokens.Identity user,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match) {
    service.delete(user.user(), id, match);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/transactions")
  public Object list(
      @AuthenticationPrincipal Tokens.Identity user,
      @RequestParam MultiValueMap<String, String> q,
      HttpServletRequest r) {
    return Responses.data(queries.list(user.user(), q), r);
  }

  @GetMapping("/categories")
  public Object categories(
      @AuthenticationPrincipal Tokens.Identity user,
      @RequestParam(required = false) String type,
      @RequestParam(defaultValue = "false") boolean includeArchived,
      HttpServletRequest r) {
    return Responses.data(service.categories(user.user(), type, includeArchived), r);
  }

  @PostMapping("/categories")
  public ResponseEntity<?> category(
      @AuthenticationPrincipal Tokens.Identity user,
      @Valid @RequestBody Writes.Category d,
      HttpServletRequest r) {
    var c = service.createCategory(user.user(), d);
    return ResponseEntity.status(201).eTag(c.get("version").toString()).body(Responses.data(c, r));
  }

  @PatchMapping("/categories/{id}")
  public ResponseEntity<?> patch(
      @AuthenticationPrincipal Tokens.Identity user,
      @PathVariable UUID id,
      @RequestHeader(value = "If-Match", required = false) String match,
      @RequestBody Map<String, Object> d,
      HttpServletRequest r) {
    return single(service.patchCategory(user.user(), id, match, d), r);
  }

  @GetMapping("/statistics/{kind}")
  public Object stats(
      @AuthenticationPrincipal Tokens.Identity user,
      @PathVariable String kind,
      @RequestParam MultiValueMap<String, String> q,
      HttpServletRequest r) {
    return Responses.data(queries.statistics(user.user(), kind, q), r);
  }

  @GetMapping("/map/markers")
  public Object map(
      @AuthenticationPrincipal Tokens.Identity user,
      @RequestParam MultiValueMap<String, String> q,
      HttpServletRequest r) {
    return Responses.data(queries.markers(user.user(), q, false), r);
  }

  @GetMapping("/map/marker-details")
  public Object detail(
      @AuthenticationPrincipal Tokens.Identity user,
      @RequestParam MultiValueMap<String, String> q,
      HttpServletRequest r) {
    return Responses.data(queries.markers(user.user(), q, true), r);
  }

  private ResponseEntity<?> single(Map<String, Object> data, HttpServletRequest r) {
    return ResponseEntity.ok().eTag(data.get("version").toString()).body(Responses.data(data, r));
  }
}
