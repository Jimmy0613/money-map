package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.RecoveryGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class BootstrapObservationIntegrationTest {
  @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

  @Test void metadataOnlyCapabilityMatchesBackendBindingAndDetectsExistingDeletionHistory() throws Exception {
    var owner = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load().migrate();
    owner.execute("CREATE ROLE bootstrap_verifier LOGIN PASSWORD 'SyntheticVerifierOnly123'");
    var verifier = new JdbcTemplate(new DriverManagerDataSource(
        postgres.getJdbcUrl(), "bootstrap_verifier", "SyntheticVerifierOnly123"));
    assertThrows(Exception.class, () -> verifier.queryForObject(
        "SELECT public.recovery_observation(true)::text", String.class));
    owner.execute("GRANT EXECUTE ON FUNCTION public.recovery_observation(boolean) TO bootstrap_verifier");
    var json = new ObjectMapper();
    var observed = json.readTree(verifier.queryForObject(
        "SELECT public.recovery_observation(true)::text", String.class));
    assertTrue(observed.get("empty").booleanValue());
    assertTrue(observed.get("stable").isNull(), "Non-Neon cannot be mistaken for a Neon timeline");
    assertEquals(RecoveryGate.targetFingerprint(owner), HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(observed.get("physical").asText().getBytes(StandardCharsets.UTF_8))));
    assertThrows(Exception.class, () -> verifier.queryForList("SELECT email,password_hash FROM public.users"));
    assertThrows(Exception.class, () -> verifier.execute("DELETE FROM privacy.deletion_ledger"));
    owner.execute("INSERT INTO privacy.deletion_ledger(user_id) VALUES ('00000000-0000-0000-0000-000000000001')");
    assertFalse(json.readTree(verifier.queryForObject(
        "SELECT public.recovery_observation(true)::text", String.class)).get("empty").booleanValue());
    assertTrue(json.readTree(verifier.queryForObject(
        "SELECT public.recovery_observation(false)::text", String.class)).get("empty").isNull());
  }
}
