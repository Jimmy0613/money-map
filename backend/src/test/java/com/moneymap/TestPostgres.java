package com.moneymap;

import java.sql.DriverManager;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;

/** Disposable loopback cluster only; Docker remains the default test environment. */
final class TestPostgres extends PostgreSQLContainer<TestPostgres> {
  private final String local = System.getenv("TEST_POSTGRES_ADMIN_URL");
  private String url;

  TestPostgres() { super("postgres:17.6"); }

  @Override public void start() {
    if (local == null) { super.start(); return; }
    if (!local.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres"))
      throw new IllegalArgumentException("Only a disposable loopback PostgreSQL cluster is allowed");
    String database = "fixture_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(local, "demo_test", "");
         var statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + database);
      url = local.substring(0, local.lastIndexOf('/') + 1) + database;
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  @Override public void stop() { if (local == null) super.stop(); }
  @Override public String getJdbcUrl() { return local == null ? super.getJdbcUrl() : url; }
  @Override public String getUsername() { return local == null ? super.getUsername() : "demo_test"; }
  @Override public String getPassword() { return local == null ? super.getPassword() : ""; }
}
