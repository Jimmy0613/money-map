package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.moneymap.auth.*;
import com.moneymap.common.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapDiagnosticsTest {
  @Test void boundedCodesAreAggregatedWithoutUserTagsAndRateLimited() {
    var meters = new SimpleMeterRegistry();
    var controller = new MapDiagnostics(meters, new RateLimits());
    var user = new Tokens.Identity(UUID.randomUUID(), UUID.randomUUID());
    for (int i = 0; i < 60; i++) controller.record(user, new MapDiagnostics.Event(MapDiagnostics.Code.SEARCH_FAILED));
    assertEquals(60, meters.get("money_map.external.errors").tag("code", "SEARCH_FAILED").counter().count());
    assertEquals(1, meters.getMeters().getFirst().getId().getTags().size());
    assertEquals(429, assertThrows(ApiException.class, () -> controller.record(user, new MapDiagnostics.Event(MapDiagnostics.Code.SEARCH_FAILED))).status);
  }
}
