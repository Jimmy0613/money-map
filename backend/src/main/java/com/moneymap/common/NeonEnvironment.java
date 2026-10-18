package com.moneymap.common;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.*;

/** Explicit opt-in: local Postgres configuration is preserved unless 'neon' is active. */
public class NeonEnvironment implements EnvironmentPostProcessor, Ordered {
  public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    if (!Arrays.asList(env.getActiveProfiles()).contains("neon")) return;
    Connection runtime = parse(env.getProperty("DATABASE_URL"), false);
    Connection migrations = parse(env.getProperty("DATABASE_URL_UNPOOLED"), true);
    if (!runtime.database.equals(migrations.database) || !runtime.user.equals(migrations.user)
        || !runtime.host.replace("-pooler.", ".").equals(migrations.host))
      throw new IllegalStateException("Neon runtime and migration targets must match");
    Map<String,Object> properties = new HashMap<>();
    properties.put("spring.datasource.url", runtime.jdbc);
    properties.put("spring.datasource.username", runtime.user);
    properties.put("spring.datasource.password", runtime.password);
    properties.put("spring.flyway.url", migrations.jdbc);
    properties.put("spring.flyway.user", migrations.user);
    properties.put("spring.flyway.password", migrations.password);
    properties.put("spring.datasource.hikari.maximum-pool-size", "3");
    properties.put("spring.datasource.hikari.minimum-idle", "0");
    properties.put("spring.datasource.hikari.idle-timeout", "30000");
    properties.put("spring.datasource.hikari.connection-timeout", "15000");
    properties.put("spring.datasource.hikari.keepalive-time", "0");
    env.getPropertySources().addFirst(new MapPropertySource("neon-datasources", properties));
  }

  public record Connection(String jdbc, String user, String password, String host, String database) {
    @Override public String toString() { return "NeonConnection[redacted]"; }
  }

  public static Connection parse(String value, boolean direct) {
    try {
      if (value == null || value.isBlank()) throw new IllegalArgumentException();
      URI uri = URI.create(value);
      String host = uri.getHost();
      if (!Set.of("postgres", "postgresql").contains(uri.getScheme()) || host == null
          || !host.endsWith(".neon.tech") || uri.getFragment() != null
          || (direct && host.contains("-pooler."))) throw new IllegalArgumentException();
      String info = uri.getRawUserInfo();
      if (info == null || !info.contains(":")) throw new IllegalArgumentException();
      String[] credentials = info.split(":", 2);
      String user = decode(credentials[0]), password = decode(credentials[1]);
      String database = uri.getRawPath();
      if (user.isBlank() || password.isBlank() || database == null
          || !database.matches("/[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
      int port = uri.getPort() == -1 ? 5432 : uri.getPort();
      if (port < 1 || port > 65535) throw new IllegalArgumentException();
      String jdbc = "jdbc:postgresql://" + host + ":" + port + database
          + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory"
          + "&channelBinding=require&connectTimeout=15&socketTimeout=30";
      return new Connection(jdbc, user, password, host, database);
    } catch (Exception ignored) {
      // URI parsing exceptions include the original connection URL: suppress their causes.
      throw new IllegalStateException("Valid Neon " + (direct ? "direct" : "runtime") + " database URL required");
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
  }
}
