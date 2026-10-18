package com.moneymap.common;

import com.moneymap.auth.RateLimits;
import com.moneymap.auth.Tokens;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/diagnostics")
public class MapDiagnostics {
  public enum Code {
    SDK_LOAD, SDK_TIMEOUT, SDK_UNAVAILABLE, SEARCH_FAILED, SEARCH_TIMEOUT,
    ADDRESS_FAILED, ADDRESS_TIMEOUT, REGION_FAILED, REGION_TIMEOUT
  }
  public record Event(@NotNull Code code) {}
  private final MeterRegistry meters;
  private final RateLimits limits;
  public MapDiagnostics(MeterRegistry meters, RateLimits limits) {
    this.meters = meters;
    this.limits = limits;
  }
  @PostMapping("/map-errors")
  public ResponseEntity<Void> record(@AuthenticationPrincipal Tokens.Identity user,
      @Valid @RequestBody Event event) {
    limits.hit("map-diagnostics:" + user.user(), 60, 60);
    meters.counter("money_map.external.errors", "code", event.code().name()).increment();
    // Fixed code only: operator logs work without exposing the metrics endpoint.
    org.slf4j.LoggerFactory.getLogger(MapDiagnostics.class).info("externalMapError={}", event.code());
    return ResponseEntity.noContent().build();
  }
}
