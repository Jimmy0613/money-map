package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.moneymap.ledger.LedgerSchemaValidator;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@Testcontainers
@Tag("integration")
class JdbcRuntimeIntegrationTest {
  @Container static TestPostgres postgres = new TestPostgres();
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("app.origin", () -> "https://example.invalid");
    r.add("app.jwt-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
    r.add("app.cursor-secret", () -> Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes()));
  }
  @Autowired JdbcTemplate db;
  @Autowired PlatformTransactionManager manager;
  @Autowired LedgerSchemaValidator validator;

  @Test void migrationThenValidationSucceedsWithoutOrm() {
    assertDoesNotThrow(validator::afterPropertiesSet);
    assertInstanceOf(DataSourceTransactionManager.class, manager);
    assertThrows(ClassNotFoundException.class, () -> Class.forName("jakarta.persistence.EntityManager"));
  }

  @Test void missingColumnIsRejectedAndDdlRollbackRestoresSchema() {
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      db.execute("ALTER TABLE transactions RENAME COLUMN memo TO incompatible_memo");
      assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
      status.setRollbackOnly();
    });
    assertDoesNotThrow(validator::afterPropertiesSet);
  }

  @Test void changedTypeIsRejectedAndDdlRollbackRestoresSchema() {
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      db.execute("ALTER TABLE transactions ALTER COLUMN memo TYPE varchar(501)");
      assertThrows(IllegalStateException.class, validator::afterPropertiesSet);
      status.setRollbackOnly();
    });
    assertDoesNotThrow(validator::afterPropertiesSet);
  }
}
