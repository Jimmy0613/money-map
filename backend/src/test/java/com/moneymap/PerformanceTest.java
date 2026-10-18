package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;

import com.moneymap.auth.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"logging.level.com.moneymap.common.RequestFilter=WARN"})
@Testcontainers
@Tag("performance")
class PerformanceTest {
  // Business contract tests isolate the recovery authority; RecoveryAuthorityIntegrationTest
  // exercises the actual gate, signed HTTP protocol, and PostgreSQL binding separately.
  @org.springframework.test.context.bean.override.mockito.MockitoBean
  com.moneymap.common.RecoveryGate recoveryGate;
  @org.junit.jupiter.api.BeforeEach
  void permitBusinessFixture() {
    org.mockito.Mockito.when(recoveryGate.allows()).thenReturn(true);
  }

  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add(
        "app.cursor-secret", () -> Base64.getEncoder().encodeToString(Tokens.random().getBytes()));
    r.add("app.cookie-secure", () -> true);
    r.add("app.origin", () -> "https://example.invalid");
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired AuthService auth;

  record Client(String access, String refresh) {}

  @Test
  void tenUsersWithTenThousandRowsForFiveMinutes() throws Exception {
    List<Client> clients = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String email = UUID.randomUUID() + "@example.invalid", password = Tokens.random() + "A1";
      UUID user =
          (UUID)
              auth.signup(
                      new AuthService.Signup(email, password, password, true, "v1"), "perf-" + i)
                  .get("id");
      UUID category =
          db.queryForObject(
              "SELECT id FROM categories WHERE user_id=? AND name='식비'", UUID.class, user);
      db.update(
          "INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT"
              + " gen_random_uuid(),?,'EXPENSE',1000,?,DATE '2026-09-15' FROM"
              + " generate_series(1,10000)",
          user,
          category);
      db.update(
          "INSERT INTO transaction_locations(transaction_id,user_id,source,latitude,longitude)"
              + " SELECT id,user_id,'MAP',37+row_number() OVER(ORDER BY id)*0.000001,127 FROM"
              + " transactions WHERE user_id=?",
          user);
      var tokens = auth.login(new AuthService.Login(email, password), "perf-" + i);
      clients.add(new Client(tokens.access(), tokens.refresh()));
    }
    db.execute("ANALYZE transactions");
    db.execute("ANALYZE transaction_locations");
    String dates = "?from=2026-09-01&to=2026-09-30";
    List<String> routes =
        List.of(
            "/transactions" + dates,
            "/statistics/summary" + dates,
            "/statistics/categories" + dates,
            "/statistics/places" + dates,
            "/statistics/regions" + dates,
            "/statistics/monthly-comparison?month=2026-09",
            "/map/markers" + dates + "&south=37&north=38&west=126&east=128&level=5");
    List<Queue<Long>> timings =
        routes.stream().map(x -> (Queue<Long>) new ConcurrentLinkedQueue<Long>()).toList();
    Queue<Integer> failures = new ConcurrentLinkedQueue<>();
    long begin = System.nanoTime(),
        warmEnd = begin + Duration.ofSeconds(60).toNanos(),
        end = warmEnd + Duration.ofSeconds(300).toNanos();
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var pool = Executors.newFixedThreadPool(10)) {
      List<Future<?>> work = new ArrayList<>();
      for (Client initial : clients)
        work.add(
            pool.submit(
                () -> {
                  Client current = initial;
                  long renew = begin + Duration.ofSeconds(240).toNanos();
                  int n = 0;
                  while (System.nanoTime() < end) {
                    if (System.nanoTime() > renew) {
                      var t = auth.refresh(current.refresh);
                      current = new Client(t.access(), t.refresh());
                      renew = System.nanoTime() + Duration.ofSeconds(240).toNanos();
                    }
                    int index = n++ % routes.size();
                    var req =
                        HttpRequest.newBuilder(
                                URI.create(
                                    "http://localhost:" + port + "/api/v1" + routes.get(index)))
                            .header("Cookie", "mm_access=" + current.access)
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build();
                    long start = System.nanoTime();
                    try {
                      var response = http.send(req, HttpResponse.BodyHandlers.discarding());
                      if (response.statusCode() != 200) failures.add(response.statusCode());
                    } catch (Exception e) {
                      failures.add(-1);
                    }
                    if (start >= warmEnd)
                      timings.get(index).add((System.nanoTime() - start) / 1000000);
                  }
                }));
      for (var f : work) f.get();
    }
    System.out.println(
        "PERFORMANCE users=10 rowsPerUser=10000 warmupSeconds=60 measurementSeconds=300"
            + " pacing=closed-loop");
    boolean pass = failures.isEmpty();
    for (int i = 0; i < routes.size(); i++) {
      var values = new ArrayList<>(timings.get(i));
      Collections.sort(values);
      long p95 =
          values.isEmpty() ? Long.MAX_VALUE : values.get((int) Math.ceil(values.size() * 0.95) - 1);
      int limit = i == 0 ? 500 : 1000;
      System.out.println(
          "PERFORMANCE route="
              + routes.get(i).split("\\?")[0]
              + " count="
              + values.size()
              + " p95Ms="
              + p95
              + " limitMs="
              + limit);
      pass &= p95 <= limit;
    }
    System.out.println("PERFORMANCE httpErrors=" + failures.size());
    assertTrue(
        pass,
        "See sanitized PERFORMANCE aggregate metrics; responses and credentials are not logged.");
  }
}
