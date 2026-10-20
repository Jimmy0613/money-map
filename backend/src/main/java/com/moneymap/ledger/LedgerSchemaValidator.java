package com.moneymap.ledger;

import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Keeps the former entity's startup column/type check without initializing an ORM. */
@Component
@DependsOnDatabaseInitialization
public class LedgerSchemaValidator implements InitializingBean {
  private static final Map<String, String> COLUMNS = Map.ofEntries(
      Map.entry("id", "uuid"), Map.entry("user_id", "uuid"),
      Map.entry("type", "character varying(7)"), Map.entry("amount", "bigint"),
      Map.entry("currency", "character(3)"), Map.entry("category_id", "uuid"),
      Map.entry("transaction_date", "date"), Map.entry("memo", "character varying(500)"),
      Map.entry("version", "bigint"), Map.entry("created_at", "timestamp with time zone"),
      Map.entry("updated_at", "timestamp with time zone"));
  private final JdbcTemplate db;

  public LedgerSchemaValidator(JdbcTemplate db) { this.db = db; }

  @Override public void afterPropertiesSet() {
    Map<String, String> actual = db.query("""
        SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod)
        FROM pg_catalog.pg_attribute a
        JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
        JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND c.relname='transactions' AND c.relkind='r'
          AND a.attnum>0 AND NOT a.attisdropped
        """, result -> {
          var columns = new java.util.HashMap<String, String>();
          while (result.next()) columns.put(result.getString(1), result.getString(2));
          return columns;
        });
    if (!actual.entrySet().containsAll(COLUMNS.entrySet()))
      throw new IllegalStateException("Transaction schema is incompatible with the JDBC contract");
  }
}
