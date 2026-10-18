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
class MutationPerformanceTest {
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

  @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
  record Client(String access, String refresh, UUID category) {}

  @Test
  void mutationPathsAtTenThousandRows() throws Exception {
    List<Client> clients = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String email = UUID.randomUUID() + "@example.invalid", password = Tokens.random() + "A1";
      UUID user = (UUID) auth.signup(new AuthService.Signup(email,password,password,true,"v1"), "mutation-perf-"+i).get("id");
      UUID category = UUID.randomUUID();
      db.update("INSERT INTO categories(id,user_id,type,name,normalized_name) VALUES (?,?,'EXPENSE','perf','perf')", category,user);
      db.update("INSERT INTO transactions(id,user_id,type,amount,category_id,transaction_date) SELECT gen_random_uuid(),?,'EXPENSE',1000,?,DATE '2026-09-15' FROM generate_series(1,10000)",user,category);
      var tokens = auth.login(new AuthService.Login(email,password), "mutation-perf-"+i);
      clients.add(new Client(tokens.access(),tokens.refresh(),category));
    }
    db.execute("ANALYZE transactions");
    String[] paths = {"POST /transactions", "GET /transactions/{id}", "PUT /transactions/{id}", "DELETE /transactions/{id}", "PATCH /categories/{id}"};
    List<Queue<Long>> timings = Arrays.stream(paths).map(x -> (Queue<Long>) new ConcurrentLinkedQueue<Long>()).toList();
    Queue<String> failures = new ConcurrentLinkedQueue<>();
    long begin = System.nanoTime(), warmEnd = begin + Duration.ofSeconds(60).toNanos(), end = warmEnd + Duration.ofSeconds(300).toNanos();
    try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(); var pool = Executors.newFixedThreadPool(10)) {
      List<Future<?>> workers = new ArrayList<>();
      for (Client initial : clients) workers.add(pool.submit(() -> {
        try {
          Client current = initial;
          String baseUrl = "http://localhost:" + port + "/api/v1";
          var csrfResponse = http.send(HttpRequest.newBuilder(URI.create(baseUrl+"/auth/csrf")).GET().build(),HttpResponse.BodyHandlers.discarding());
          String csrfCookie = csrfResponse.headers().allValues("set-cookie").stream().filter(x -> x.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow().split(";",2)[0];
          String csrf = java.net.URLDecoder.decode(csrfCookie.substring("XSRF-TOKEN=".length()),java.nio.charset.StandardCharsets.UTF_8);
          long renew = begin + Duration.ofSeconds(240).toNanos(), categoryVersion = 0;
          String id = null;
          int index = 0;
          while (System.nanoTime() < end) {
            if (System.nanoTime() > renew) {
              var tokens = auth.refresh(current.refresh);
              current = new Client(tokens.access(),tokens.refresh(),current.category);
              renew = System.nanoTime()+Duration.ofSeconds(240).toNanos();
            }
            String route = index == 0 ? "/transactions" : index == 4 ? "/categories/"+current.category : "/transactions/"+id;
            var request = HttpRequest.newBuilder(URI.create(baseUrl+route)).timeout(Duration.ofSeconds(15))
                .header("Cookie","mm_access="+current.access+"; "+csrfCookie)
                .header("Origin","https://example.invalid").header("X-XSRF-TOKEN",csrf).header("Content-Type","application/json");
            String transaction = json.writeValueAsString(new com.moneymap.ledger.Writes.Transaction("EXPENSE", index==2 ? "1001" : "1000", "KRW", current.category, "2026-09-15", null, null));
            if (index==0) request.header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(transaction));
            else if(index==1) request.GET();
            else if(index==2) request.header("If-Match","\"0\"").PUT(HttpRequest.BodyPublishers.ofString(transaction));
            else if(index==3) request.header("If-Match","\"1\"").DELETE();
            else request.header("If-Match","\""+categoryVersion+"\"").method("PATCH",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("name","perf-"+(categoryVersion+1)))));
            long started = System.nanoTime();
            var response = http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            long elapsed = (System.nanoTime()-started)/1000000;
            if(started>=warmEnd) timings.get(index).add(elapsed);
            int expected = index==0 ? 201 : index==3 ? 204 : 200;
            if(response.statusCode()!=expected) { failures.add(index+":"+response.statusCode()); break; }
            if(index==0) id=json.readTree(response.body()).path("data").path("id").asText();
            if(index==4) categoryVersion++;
            index=(index+1)%paths.length;
          }
        } catch(Exception ignored) { failures.add(ignored.getClass().getSimpleName()); }
      }));
      for(var worker:workers) worker.get();
    }
    boolean passed=failures.isEmpty();
    System.out.println("MUTATION_PERFORMANCE users=10 seedRowsPerUser=10000 warmupSeconds=60 measurementSeconds=300 pacing=closed-loop timing=loopback-http-wall-clock");
    for(int i=0;i<paths.length;i++) {
      var values=new ArrayList<>(timings.get(i)); Collections.sort(values);
      long p95=values.isEmpty()?Long.MAX_VALUE:values.get((int)Math.ceil(values.size()*0.95)-1);
      System.out.println("MUTATION_PERFORMANCE route="+paths[i]+" count="+values.size()+" p95Ms="+p95+" limitMs=500");
      passed &= values.size()>=100 && p95<=500;
    }
    System.out.println("MUTATION_PERFORMANCE httpErrors="+failures.size()+" types="+new TreeSet<>(failures));
    assertTrue(passed,"See sanitized aggregate mutation performance metrics");
  }
}
