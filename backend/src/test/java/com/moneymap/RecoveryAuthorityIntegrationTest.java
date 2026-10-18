package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@Tag("integration")
class RecoveryAuthorityIntegrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
  static JdbcTemplate db;
  final ObjectMapper json = new ObjectMapper();
  HttpServer server;
  KeyPair key;
  MockEnvironment config;
  RecoveryGate gate;
  Secrets secrets;
  String registeredTarget;
  Consumer<Map<String,Object>> mutate = p -> {};
  boolean corruptSignature, huge, unavailable;
  final AtomicReference<byte[]> replay = new AtomicReference<>();
  boolean reuse;
  int generation = 1;

  @BeforeAll static void schema() {
    var datasource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(datasource).load().migrate();
    db = new JdbcTemplate(datasource);
  }

  @BeforeEach void setup() throws Exception {
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    registeredTarget = RecoveryGate.targetFingerprint(db);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/decision", exchange -> {
      try {
        Map<String,Object> request = json.readValue(exchange.getRequestBody(), Map.class);
        long now = Instant.now().getEpochSecond();
        Map<String,Object> proof = new HashMap<>(request);
        proof.put("version", 1); proof.put("state", "RELEASED"); proof.put("generation", generation);
        proof.put("target", registeredTarget); proof.put("issuedAt", now); proof.put("expiresAt", now + 5);
        mutate.accept(proof);
        byte[] payload = json.writeValueAsBytes(proof);
        var signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(payload);
        byte[] signature = signer.sign(); if (corruptSignature) signature[0] ^= 1;
        byte[] response = json.writeValueAsBytes(Map.of("payload", Base64.getEncoder().encodeToString(payload),
            "signature", Base64.getEncoder().encodeToString(signature)));
        if (reuse && replay.get() != null) response = replay.get(); else replay.set(response);
        if (huge) response = new byte[9000];
        exchange.sendResponseHeaders(unavailable ? 503 : 200, response.length);
        exchange.getResponseBody().write(response);
      } catch (Exception ignored) { exchange.sendResponseHeaders(503, -1); }
      finally { exchange.close(); }
    });
    server.start();
    config = new MockEnvironment()
        .withProperty("app.recovery.authority-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/decision")
        .withProperty("app.recovery.public-key", Base64.getEncoder().encodeToString(key.getPublic().getEncoded()))
        .withProperty("app.recovery.environment", "synthetic-test")
        .withProperty("app.recovery.deployment", "candidate-1");
    byte[] a = new byte[32], b = new byte[32]; Arrays.fill(b, (byte)1);
    secrets = new Secrets(Base64.getEncoder().encodeToString(a), Base64.getEncoder().encodeToString(b),
        "https://example.invalid", true, config);
    gate = new RecoveryGate(secrets, db, json, config);
  }
  @AfterEach void cleanup() { if (server != null) server.stop(0); }

  @Test void validDecisionPassesActualFilter() throws Exception {
    var response = new MockHttpServletResponse();
    new RequestFilter(secrets, json, gate::allows).doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/csrf"),
        response, (r,w) -> w.getWriter().write("reached"));
    assertEquals("reached", response.getContentAsString());
  }
  @Test void signedOldChallengeCannotBeReplayed() { assertTrue(gate.allows()); reuse = true; assertFalse(gate.allows()); }
  @Test void authorityFailureNeverUsesPreviousPermit() {
    assertTrue(gate.allows()); unavailable = true; assertFalse(gate.allows());
    server.stop(0); assertFalse(gate.allows());
  }
  @Test void expiredAndFutureProofsFail() {
    mutate = p -> p.put("expiresAt", Instant.now().getEpochSecond() - 1); assertFalse(gate.allows());
    mutate = p -> p.put("issuedAt", Instant.now().getEpochSecond() + 20); assertFalse(gate.allows());
    mutate = p -> p.put("expiresAt", Instant.now().getEpochSecond() + 60); assertFalse(gate.allows());
  }
  @Test void targetEnvironmentDeploymentAndStateAreBound() {
    for (String field : List.of("target", "environment", "deployment", "state")) {
      mutate = p -> p.put(field, "mismatch"); assertFalse(gate.allows(), field);
    }
    mutate = p -> p.put("generation", 0); assertFalse(gate.allows());
  }
  @Test void newGenerationCanBlockPreviouslyReleasedCandidate() {
    assertTrue(gate.allows()); generation = 2;
    mutate = p -> p.put("state", "BLOCKED"); assertFalse(gate.allows());
  }
  @Test void tamperingAndOversizedResponseFail() {
    corruptSignature = true; assertFalse(gate.allows());
    corruptSignature = false; huge = true; assertFalse(gate.allows());
  }
  @Test void wrongPinnedKeyAndRemotePlainHttpFail() throws Exception {
    var other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    config.withProperty("app.recovery.public-key", Base64.getEncoder().encodeToString(other.getPublic().getEncoded()));
    assertFalse(new RecoveryGate(secrets, db, json, config).allows());
    config.withProperty("app.recovery.authority-url", "http://example.invalid/decision");
    assertFalse(new RecoveryGate(secrets, db, json, config).allows());
  }
  @Test void schemaChangeInvalidatesBinding() {
    assertTrue(gate.allows());
    db.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE installed_rank=1");
    try { assertFalse(gate.allows()); }
    finally { db.update("UPDATE flyway_schema_history SET checksum=checksum-1 WHERE installed_rank=1"); }
  }
  @Test void databaseRestartRequiresRevalidation() throws Exception {
    assertTrue(gate.allows());
    org.testcontainers.DockerClientFactory.instance().client()
        .restartContainerCmd(postgres.getContainerId()).exec();
    var docker = org.testcontainers.DockerClientFactory.instance().client();
    String port = docker.inspectContainerCmd(postgres.getContainerId()).exec().getNetworkSettings()
        .getPorts().getBindings().get(new com.github.dockerjava.api.model.ExposedPort(5432))[0].getHostPortSpec();
    db = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":" + port
        + "/" + postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword()));
    String current = null;
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      try { current = RecoveryGate.targetFingerprint(db); break; }
      catch (Exception unavailable) { Thread.sleep(100); }
    }
    assertNotNull(current);
    assertNotEquals(registeredTarget, current);
    assertFalse(new RecoveryGate(secrets, db, json, config).allows());
  }

  @Test void databaseReplacementInvalidatesBinding() {
    db.execute("CREATE DATABASE recovery_other");
    try {
      var other = new DriverManagerDataSource(((DriverManagerDataSource) db.getDataSource()).getUrl().replace('/' + postgres.getDatabaseName(), "/recovery_other"),
          postgres.getUsername(), postgres.getPassword());
      Flyway.configure().dataSource(other).load().migrate();
      assertFalse(new RecoveryGate(secrets, new JdbcTemplate(other), json, config).allows());
    } finally { db.execute("DROP DATABASE recovery_other"); }
  }
}
