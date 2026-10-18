package com.moneymap.ledger;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
  final JdbcTemplate db;

  public LedgerRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> query(String sql, Object... args) {
    return db.queryForList(sql, args);
  }

  public int update(String sql, Object... args) {
    return db.update(sql, args);
  }

  public Map<String, Object> user(UUID user, boolean lock) {
    var rows =
        query(
            "SELECT id,timezone,data_revision FROM users WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            user);
    if (rows.isEmpty()) throw new com.moneymap.common.ApiException(401, "UNAUTHENTICATED");
    return rows.getFirst();
  }

  public void revision(UUID user) {
    update("UPDATE users SET data_revision=data_revision+1,updated_at=now() WHERE id=?", user);
  }

  public static final String SELECT =
      """
      SELECT t.*,c.name category_name,c.archived_at category_archived,l.source,l.place_id,l.latitude,l.longitude,l.accuracy_m,l.captured_at,l.low_accuracy_accepted,l.place_name,l.road_address,l.parcel_address,l.region_code,l.region_sido,l.region_sigungu,l.region_key,p.external_id
      FROM transactions t JOIN categories c ON c.id=t.category_id AND c.user_id=t.user_id
      LEFT JOIN transaction_locations l ON l.transaction_id=t.id AND l.user_id=t.user_id
      LEFT JOIN places p ON p.id=l.place_id AND p.user_id=t.user_id
      """;
}
