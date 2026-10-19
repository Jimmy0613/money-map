package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class RecoveryCapacityTest {
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private RecoveryGate gate() {
    var env = new MockEnvironment().withProperty("app.recovery.environment", "fixture")
        .withProperty("app.recovery.deployment", "fixture")
        .withProperty("app.recovery.authority-url", "https://example.invalid/decision")
        .withProperty("app.recovery.public-key", "invalid-key");
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte) 1);
    return new RecoveryGate(new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "https://example.invalid", true, env), db, new ObjectMapper(), env);
  }
  private Semaphore permits(RecoveryGate gate) throws Exception {
    var field = RecoveryGate.class.getDeclaredField("inFlight"); field.setAccessible(true);
    return (Semaphore) field.get(gate);
  }
  private void awaitQueue(Semaphore permits) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!permits.hasQueuedThreads() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(permits.hasQueuedThreads());
  }
  @Test void burstWaitsForCapacityButNeverAcceptsInvalidProofAndReturnsPermit() throws Exception {
    var gate = gate(); var permits = permits(gate); permits.acquire(8);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var request = executor.submit(gate::allows);
      awaitQueue(permits); assertFalse(request.isDone());
      permits.release();
      assertFalse(request.get(2, TimeUnit.SECONDS));
      assertEquals(1, permits.availablePermits());
      verifyNoInteractions(db);
    } finally { permits.release(7); }
    assertEquals(8, permits.availablePermits());
  }
  @Test void saturatedQueueIsBoundedAndDoesNotReadDatabase() throws Exception {
    var gate = gate(); var permits = permits(gate); permits.acquire(8);
    long start = System.nanoTime();
    try {
      assertFalse(gate.allows());
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsed >= 2800 && elapsed < 6000);
      assertEquals(0, permits.availablePermits()); verifyNoInteractions(db);
    } finally { permits.release(8); }
  }
  @Test void interruptedQueueStaysClosedAndDoesNotLeakCapacity() throws Exception {
    var gate = gate(); var permits = permits(gate); permits.acquire(8);
    var result = new CompletableFuture<Boolean>();
    var thread = Thread.ofVirtual().start(() -> result.complete(gate.allows()));
    try {
      awaitQueue(permits); thread.interrupt();
      assertFalse(result.get(2, TimeUnit.SECONDS));
      assertEquals(0, permits.availablePermits()); verifyNoInteractions(db);
    } finally { permits.release(8); }
  }
}
