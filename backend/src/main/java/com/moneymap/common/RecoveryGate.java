package com.moneymap.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** No cached permit: each protected request needs a fresh, nonce-bound authority decision. */
@Component
public class RecoveryGate {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RecoveryGate.class);
  private final Secrets secrets;
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final String environment, deployment, endpoint, encodedKey;
  private final String authorityToken;
  private final String runtimeRevision;
  private final int decisionTimeout;
  private final HttpClient http;
  private final Semaphore inFlight = new Semaphore(8, true);

  public RecoveryGate(Secrets secrets, JdbcTemplate db, ObjectMapper json, Environment config) {
    this.secrets = secrets;
    this.db = db;
    this.json = json;
    environment = config.getProperty("app.recovery.environment", "");
    deployment = config.getProperty("app.recovery.deployment", "");
    endpoint = config.getProperty("app.recovery.authority-url", "");
    encodedKey = config.getProperty("app.recovery.public-key", "");
    authorityToken = config.getProperty("app.recovery.authority-token", "");
    runtimeRevision = config.getProperty("RENDER_GIT_COMMIT", "");
    decisionTimeout = config.getProperty("app.recovery.timeout-ms", Integer.class, 750);
    if (decisionTimeout < 100 || decisionTimeout > 5000)
      throw new IllegalStateException("Invalid recovery timeout");
    http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(2000, decisionTimeout - 50)))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public boolean allows() {
    if (secrets.recoveryRequired) return false;
    if (secrets.local) return true;
    if (!runtimeRevision.isBlank() && !runtimeRevision.equals(deployment)) return false;
    if (environment.isBlank() || deployment.isBlank() || endpoint.isBlank() || encodedKey.isBlank())
      return false;
    // A page can issue several reads together. Queue briefly without increasing
    // authority concurrency, caching decisions, or allowing unverified requests.
    try {
      if (!inFlight.tryAcquire(3000, TimeUnit.MILLISECONDS)) return denied("CAPACITY");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      URI uri = URI.create(endpoint);
      if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
          || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
              && Set.of("127.0.0.1", "[::1]").contains(uri.getHost())))) return false;
      PublicKey key = KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)));
      String target = targetFingerprint(db);
      String nonce = UUID.randomUUID().toString();
      byte[] request = json.writeValueAsBytes(Map.of("environment", environment,
          "deployment", deployment, "target", target, "nonce", nonce));
      var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(decisionTimeout - 50))
          .header("Content-Type", "application/json");
      if (!authorityToken.isBlank()) builder.header("Authorization", "Bearer " + authorityToken);
      pending = http.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofByteArray(request))
          .build(), ignored -> new BoundedBody());
      var response = pending.get(decisionTimeout, TimeUnit.MILLISECONDS);
      if (response.statusCode() != 200) return denied("AUTHORITY_STATUS_" + response.statusCode());
      var envelope = json.readTree(response.body());
      if (!envelope.isObject() || envelope.size() != 2
          || !envelope.path("payload").isTextual() || !envelope.path("signature").isTextual()) return false;
      byte[] payload = Base64.getDecoder().decode(envelope.get("payload").textValue());
      Signature signature = Signature.getInstance("Ed25519");
      signature.initVerify(key);
      signature.update(payload);
      if (!signature.verify(Base64.getDecoder().decode(envelope.get("signature").textValue()))) return false;
      var proof = json.readTree(payload);
      long now = Instant.now().getEpochSecond();
      return proof.path("version").asInt() == 1
          && "RELEASED".equals(proof.path("state").asText())
          && environment.equals(proof.path("environment").asText())
          && deployment.equals(proof.path("deployment").asText())
          && target.equals(proof.path("target").asText())
          && nonce.equals(proof.path("nonce").asText())
          && proof.path("generation").canConvertToLong() && proof.path("generation").asLong() > 0
          && proof.path("issuedAt").canConvertToLong() && proof.path("expiresAt").canConvertToLong()
          && proof.path("issuedAt").asLong() <= now
          && proof.path("issuedAt").asLong() >= now - 5
          && proof.path("expiresAt").asLong() > now
          && proof.path("expiresAt").asLong() <= proof.path("issuedAt").asLong() + 5;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception e) {
      // Never log the URL, key, response, DB identity, or SQL error contents.
      return denied(e instanceof java.util.concurrent.TimeoutException ? "DECISION_TIMEOUT"
          : e instanceof java.util.concurrent.ExecutionException ? "AUTHORITY_CONNECTION"
          : e instanceof org.springframework.dao.DataAccessException ? "DATABASE_CHECK"
          : "VALIDATION");
    } finally {
      if (pending != null && !pending.isDone()) pending.cancel(true);
      inFlight.release();
    }
  }

  private boolean denied(String reason) {
    LOG.warn("recoveryCheckDenied reason={}", reason);
    return false;
  }

  /** Restart/replacement changes the binding and requires authority revalidation, never auto-release. */
  public static String targetFingerprint(JdbcTemplate db) throws Exception {
    String identity = db.query(connection -> {
      var statement = connection.prepareStatement("SELECT c.system_identifier::text || ':' || "
          + "d.oid::text || ':' || (extract(epoch from pg_postmaster_start_time())*1000000)::bigint::text"
          + " || ':' || coalesce((SELECT string_agg(installed_rank::text || ':' || "
          + "coalesce(checksum::text,'') || ':' || success::text, ',' ORDER BY installed_rank)"
          + " FROM public.flyway_schema_history),'') FROM pg_control_system() c, pg_database d"
          + " WHERE d.datname=current_database()");
      statement.setQueryTimeout(1);
      return statement;
    }, result -> result.next() ? result.getString(1) : null);
    if (identity == null) throw new IllegalStateException("Missing database binding");
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(identity.getBytes(StandardCharsets.UTF_8)));
  }

  private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return body; }
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(1);
    }
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > 8192 - bytes.size()) {
          subscription.cancel();
          body.completeExceptionally(new IllegalStateException("Authority response too large"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { body.completeExceptionally(error); }
    public void onComplete() { body.complete(bytes.toByteArray()); }
  }
}
