package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.moneymap.common.NeonEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NeonEnvironmentTest {
  static final String DIRECT = "postgresql://test:p%40ss%2Bword@ep-test.aws.neon.tech/neondb?sslmode=require";
  static final String POOLED = DIRECT.replace("ep-test.", "ep-test-pooler.");
  @Test void neonProfileUsesPooledRuntimeDirectMigrationsAndVerifiedTls() {
    var env = new MockEnvironment(); env.setActiveProfiles("neon");
    env.withProperty("DATABASE_URL", POOLED).withProperty("DATABASE_URL_UNPOOLED", DIRECT)
        .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/keep");
    new NeonEnvironment().postProcessEnvironment(env, null);
    assertTrue(env.getProperty("spring.datasource.url").contains("-pooler."));
    assertFalse(env.getProperty("spring.flyway.url").contains("-pooler."));
    assertTrue(env.getProperty("spring.flyway.url").contains("sslmode=verify-full"));
    assertEquals("p@ss+word", env.getProperty("spring.datasource.password"));
    assertEquals("3", env.getProperty("spring.datasource.hikari.maximum-pool-size"));
    assertFalse(env.getProperty("spring.datasource.url").contains("p%40ss"));
  }
  @Test void existingLocalConfigurationIsNotChangedWithoutExplicitProfile() {
    var env = new MockEnvironment().withProperty("spring.datasource.url", "original");
    new NeonEnvironment().postProcessEnvironment(env, null);
    assertEquals("original", env.getProperty("spring.datasource.url"));
  }
  @Test void invalidOrMissingUrlsNeverLeakCredentialsInErrors() {
    for (String value : new String[]{null,"", "postgresql://private-marker@", DIRECT.replace(".neon.tech", ".example.invalid")}) {
      var error = assertThrows(IllegalStateException.class, () -> NeonEnvironment.parse(value, false));
      assertNull(error.getCause()); assertFalse(error.toString().contains("private-marker"));
    }
    assertThrows(IllegalStateException.class, () -> NeonEnvironment.parse(POOLED, true));
    assertEquals("NeonConnection[redacted]", NeonEnvironment.parse(DIRECT, true).toString());
  }
  @Test void mismatchedMigrationEndpointIsRejected() {
    var env = new MockEnvironment(); env.setActiveProfiles("neon");
    env.withProperty("DATABASE_URL", POOLED)
        .withProperty("DATABASE_URL_UNPOOLED", DIRECT.replace("ep-test.", "ep-other."));
    assertThrows(IllegalStateException.class, () -> new NeonEnvironment().postProcessEnvironment(env, null));
  }
}
