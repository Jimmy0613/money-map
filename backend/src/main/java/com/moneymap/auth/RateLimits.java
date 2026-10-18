package com.moneymap.auth;

import com.moneymap.common.ApiException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RateLimits {
  private record Entry(int count, long until) {}

  private final Map<String, Entry> counters = new HashMap<>();

  public synchronized void hit(String key, int limit, long seconds) {
    long now = System.currentTimeMillis();
    counters.entrySet().removeIf(e -> e.getValue().until < now);
    Entry e = counters.get(key);
    if (e == null) {
      if (counters.size() >= 10000) throw new ApiException(429, "RATE_LIMITED");
      e = new Entry(0, now + seconds * 1000);
    }
    if (e.count >= limit) throw new ApiException(429, "RATE_LIMITED");
    counters.put(key, new Entry(e.count + 1, e.until));
  }

  public synchronized void clear(String key) {
    counters.remove(key);
  }
}
