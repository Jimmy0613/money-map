package com.moneymap.ledger;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class LedgerTransaction {
  @Id UUID id;

  @Column(name = "user_id", nullable = false)
  UUID userId;

  @Column(nullable = false, length = 7)
  String type;

  @Column(nullable = false)
  long amount;

  @Column(nullable = false, columnDefinition = "char(3)")
  @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
  String currency;

  @Column(name = "category_id", nullable = false)
  UUID categoryId;

  @Column(name = "transaction_date", nullable = false)
  LocalDate transactionDate;

  @Column(length = 500)
  String memo;

  @Version long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected LedgerTransaction() {}
}
