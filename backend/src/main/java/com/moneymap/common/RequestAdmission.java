package com.moneymap.common;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** A bounded, process-wide budget before DB and authority work; no client headers are trusted. */
public final class RequestAdmission {
  private final int requestLimit, mutationLimit;
  private final LongSupplier clock;
  private long started;
  private int requests, mutations;

  public RequestAdmission(int requestLimit, int mutationLimit) {
    this(requestLimit, mutationLimit, System::nanoTime);
  }

  RequestAdmission(int requestLimit, int mutationLimit, LongSupplier clock) {
    if (requestLimit < 1 || mutationLimit < 1 || mutationLimit > requestLimit)
      throw new IllegalArgumentException("Invalid request budget");
    this.requestLimit = requestLimit;
    this.mutationLimit = mutationLimit;
    this.clock = clock;
    started = clock.getAsLong();
  }

  public synchronized void check(boolean mutation) {
    long now = clock.getAsLong();
    if (now - started >= TimeUnit.MINUTES.toNanos(1)) {
      started = now;
      requests = mutations = 0;
    }
    if (requests >= requestLimit || (mutation && mutations >= mutationLimit))
      throw new ApiException(429, "RATE_LIMITED");
    requests++;
    if (mutation) mutations++;
  }
}
