package com.moneymap.ledger;

import static com.moneymap.common.Rules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.auth.Tokens;
import com.moneymap.auth.AccountAccessPolicy;
import com.moneymap.auth.RateLimits;
import com.moneymap.common.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LedgerService {
  private final LedgerRepository repo;
  private final ObjectMapper json;
  private final AccountAccessPolicy access;
  private final RateLimits limits;

  public LedgerService(LedgerRepository repo, ObjectMapper json, AccountAccessPolicy access,
      RateLimits limits) {
    this.repo = repo;
    this.json = json;
    this.access = access;
    this.limits = limits;
  }

  private boolean demoWrite(UUID user, Map<String, Object> owner) {
    boolean demo = access.isDemo((String) owner.get("email"));
    if (demo) limits.hit("demo-write:" + user, 30, 60);
    return demo;
  }

  private void demoQuota(UUID user, String table, int maximum) {
    var rows = repo.query("SELECT count(*) AS count FROM " + table + " WHERE user_id=?", user);
    if (((Number) rows.getFirst().get("count")).longValue() >= maximum)
      throw new ApiException(429, "DEMO_QUOTA_EXCEEDED");
  }

  public record Created(Map<String, Object> transaction, boolean replay) {}

  public Map<String, Object> get(UUID user, UUID id) {
    var rows = repo.query(LedgerRepository.SELECT + " WHERE t.user_id=? AND t.id=?", user, id);
    if (rows.isEmpty()) throw ApiException.missing();
    return view(rows.getFirst());
  }

  @Transactional
  public Created create(UUID user, UUID key, Writes.Transaction input) {
    var owner = repo.user(user, true);
    var w = input.normalized(LocalDate.now(ZoneId.of((String) owner.get("timezone"))));
    String hash;
    try {
      hash = Tokens.hash(json.writeValueAsString(w));
    } catch (Exception e) {
      throw new IllegalStateException();
    }
    var old =
        repo.query(
            "SELECT body_hash,transaction_id FROM operation_keys WHERE user_id=? AND request_key=?",
            user,
            key);
    if (!old.isEmpty()) {
      var k = old.getFirst();
      if (!hash.equals(k.get("body_hash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT");
      try {
        return new Created(get(user, (UUID) k.get("transaction_id")), true);
      } catch (ApiException e) {
        throw new ApiException(409, "OPERATION_DELETED");
      }
    }
    // The user row is already locked: concurrent creates cannot exceed the persistent cap.
    // Count operation keys, including deleted transactions, so delete/create churn is bounded.
    if (demoWrite(user, owner)) demoQuota(user, "operation_keys", access.demoCreates());
    category(user, w.categoryId(), w.type(), null);
    UUID id = UUID.randomUUID();
    repo.update(
        "INSERT INTO"
            + " transactions(id,user_id,type,amount,currency,category_id,transaction_date,memo)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        user,
        w.type(),
        amount(w.amount()),
        w.currency(),
        w.categoryId(),
        date(w.transactionDate()),
        w.memo());
    location(user, id, w);
    repo.update(
        "INSERT INTO operation_keys(user_id,request_key,body_hash,transaction_id) VALUES (?,?,?,?)",
        user,
        key,
        hash,
        id);
    repo.revision(user);
    return new Created(get(user, id), false);
  }

  @Transactional
  public Map<String, Object> replace(UUID user, UUID id, String match, Writes.Transaction input) {
    var owner = repo.user(user, true);
    demoWrite(user, owner);
    var current = get(user, id);
    long v = version(match);
    if (((Number) current.get("version")).longValue() != v)
      throw new ApiException(412, "VERSION_CONFLICT");
    var w = input.normalized(LocalDate.now(ZoneId.of((String) owner.get("timezone"))));
    @SuppressWarnings("unchecked")
    var c = (Map<String, Object>) current.get("category");
    category(user, w.categoryId(), w.type(), (UUID) c.get("id"));
    repo.update(
        "UPDATE transactions SET"
            + " type=?,amount=?,category_id=?,transaction_date=?,memo=?,version=version+1,updated_at=now()"
            + " WHERE user_id=? AND id=?",
        w.type(),
        amount(w.amount()),
        w.categoryId(),
        date(w.transactionDate()),
        w.memo(),
        user,
        id);
    repo.update("DELETE FROM transaction_locations WHERE user_id=? AND transaction_id=?", user, id);
    location(user, id, w);
    cleanup(user);
    repo.revision(user);
    return get(user, id);
  }

  @Transactional
  public void delete(UUID user, UUID id, String match) {
    demoWrite(user, repo.user(user, true));
    var t = get(user, id);
    if (((Number) t.get("version")).longValue() != version(match))
      throw new ApiException(412, "VERSION_CONFLICT");
    repo.update("DELETE FROM transactions WHERE user_id=? AND id=?", user, id);
    cleanup(user);
    repo.revision(user);
  }

  private void cleanup(UUID user) {
    repo.update(
        "DELETE FROM places p WHERE p.user_id=? AND NOT EXISTS (SELECT 1 FROM transaction_locations"
            + " l WHERE l.user_id=p.user_id AND l.place_id=p.id)",
        user);
  }

  private void category(UUID user, UUID id, String type, UUID previous) {
    var rows =
        repo.query("SELECT type,archived_at FROM categories WHERE user_id=? AND id=?", user, id);
    if (rows.isEmpty()) throw ApiException.missing();
    var c = rows.getFirst();
    if (!type.equals(c.get("type")))
      throw ApiException.invalid("categoryId", "INVALID_VALUE", "거래 유형에 맞는 카테고리를 선택하세요.");
    if (c.get("archived_at") != null && !id.equals(previous))
      throw new ApiException(409, "CATEGORY_CONFLICT");
  }

  private void location(UUID user, UUID id, Writes.Transaction w) {
    var l = w.location();
    if (l == null) return;
    UUID place = null;
    if (l.externalPlaceId() != null) {
      repo.update(
          "INSERT INTO places(id,user_id,external_id) VALUES (?,?,?) ON"
              + " CONFLICT(user_id,provider,external_id) DO NOTHING",
          UUID.randomUUID(),
          user,
          l.externalPlaceId());
      place =
          (UUID)
              repo.query(
                      "SELECT id FROM places WHERE user_id=? AND provider='KAKAO' AND"
                          + " external_id=?",
                      user,
                      l.externalPlaceId())
                  .getFirst()
                  .get("id");
    }
    String key = region(l, date(w.transactionDate()));
    repo.update(
        "INSERT INTO"
            + " transaction_locations(transaction_id,user_id,source,place_id,latitude,longitude,accuracy_m,captured_at,low_accuracy_accepted,place_name,road_address,parcel_address,region_code,region_sido,region_sigungu,region_key,canonical_region_key)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        user,
        l.source(),
        place,
        new BigDecimal(l.latitude()),
        new BigDecimal(l.longitude()),
        l.accuracyM() == null ? null : new BigDecimal(l.accuracyM()),
        l.capturedAt() == null ? null : java.sql.Timestamp.from(Instant.parse(l.capturedAt())),
        l.lowAccuracyAccepted(),
        l.placeName(),
        l.roadAddress(),
        l.parcelAddress(),
        l.regionCode(),
        l.regionSido(),
        l.regionSigungu(),
        key,
        key.equals("UNKNOWN") ? null : key);
  }

  private String region(Writes.Location l, LocalDate day) {
    if (l.roadAddress() == null && l.parcelAddress() == null) return "UNKNOWN";
    if (l.regionCode() != null) {
      var rows =
          repo.query(
              "SELECT region_key FROM region_dictionary WHERE region_code=?", l.regionCode());
      return rows.isEmpty() ? "UNKNOWN" : (String) rows.getFirst().get("region_key");
    }
    if (l.regionSido() == null) return "UNKNOWN";
    var rows =
        repo.query(
            "SELECT DISTINCT region_key FROM region_aliases WHERE sido_normalized=? AND"
                + " sigungu_normalized=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>=?)",
            l.regionSido().toLowerCase(Locale.ROOT),
            Objects.toString(l.regionSigungu(), "").toLowerCase(Locale.ROOT),
            day,
            day);
    return rows.size() == 1 ? (String) rows.getFirst().get("region_key") : "UNKNOWN";
  }

  public Map<String, Object> categories(UUID user, String type, boolean archived) {
    if (type != null) Rules.type(type);
    return map(
        "items",
        repo
            .query(
                "SELECT * FROM categories WHERE user_id=?"
                    + (type == null ? "" : " AND type=?")
                    + (archived ? "" : " AND archived_at IS NULL")
                    + " ORDER BY sort_order,id",
                type == null ? new Object[] {user} : new Object[] {user, type})
            .stream()
            .map(this::categoryView)
            .toList());
  }

  @Transactional
  public Map<String, Object> createCategory(UUID user, Writes.Category w) {
    var owner = repo.user(user, true);
    if (demoWrite(user, owner)) demoQuota(user, "categories", access.demoCategories());
    String name = required(w.name(), 30);
    Rules.type(w.type());
    Writes.style(w.icon(), w.color());
    UUID id = UUID.randomUUID();
    int n =
        repo.update(
            "INSERT INTO categories(id,user_id,type,name,normalized_name,icon,color) VALUES"
                + " (?,?,?,?,?,?,?) ON CONFLICT(user_id,type,normalized_name) DO NOTHING",
            id,
            user,
            w.type(),
            name,
            name.toLowerCase(Locale.ROOT),
            w.icon(),
            w.color());
    if (n == 0) throw new ApiException(409, "CATEGORY_CONFLICT");
    repo.revision(user);
    return categoryView(
        repo.query("SELECT * FROM categories WHERE id=? AND user_id=?", id, user).getFirst());
  }

  @Transactional
  public Map<String, Object> patchCategory(
      UUID user, UUID id, String match, Map<String, Object> patch) {
    demoWrite(user, repo.user(user, true));
    var rows = repo.query("SELECT * FROM categories WHERE user_id=? AND id=?", user, id);
    if (rows.isEmpty()) throw ApiException.missing();
    var c = rows.getFirst();
    if (((Number) c.get("version")).longValue() != version(match))
      throw new ApiException(412, "VERSION_CONFLICT");
    if (Boolean.TRUE.equals(c.get("is_default")) || c.get("archived_at") != null)
      throw new ApiException(409, "CATEGORY_CONFLICT");
    if (patch.keySet().equals(Set.of("archived")) && Boolean.TRUE.equals(patch.get("archived")))
      repo.update(
          "UPDATE categories SET archived_at=now(),version=version+1,updated_at=now() WHERE"
              + " user_id=? AND id=?",
          user,
          id);
    else {
      if (patch.isEmpty() || !Set.of("name", "icon", "color").containsAll(patch.keySet()))
        throw ApiException.invalid();
      for (Object v : patch.values())
        if (v != null && !(v instanceof String)) throw ApiException.invalid();
      String name =
          patch.containsKey("name")
              ? required((String) patch.get("name"), 30)
              : (String) c.get("name");
      String icon = patch.containsKey("icon") ? (String) patch.get("icon") : (String) c.get("icon"),
          color =
              patch.containsKey("color") ? (String) patch.get("color") : (String) c.get("color");
      Writes.style(icon, color);
      if (!repo.query(
              "SELECT id FROM categories WHERE user_id=? AND type=? AND normalized_name=? AND"
                  + " id<>?",
              user,
              c.get("type"),
              name.toLowerCase(Locale.ROOT),
              id)
          .isEmpty()) throw new ApiException(409, "CATEGORY_CONFLICT");
      repo.update(
          "UPDATE categories SET"
              + " name=?,normalized_name=?,icon=?,color=?,version=version+1,updated_at=now() WHERE"
              + " user_id=? AND id=?",
          name,
          name.toLowerCase(Locale.ROOT),
          icon,
          color,
          user,
          id);
    }
    repo.revision(user);
    return categoryView(
        repo.query("SELECT * FROM categories WHERE user_id=? AND id=?", user, id).getFirst());
  }

  private Map<String, Object> categoryView(Map<String, Object> c) {
    return map(
        "id",
        c.get("id"),
        "type",
        c.get("type"),
        "name",
        c.get("name"),
        "isDefault",
        c.get("is_default"),
        "archived",
        c.get("archived_at") != null,
        "icon",
        c.get("icon"),
        "color",
        c.get("color"),
        "sortOrder",
        c.get("sort_order"),
        "version",
        c.get("version"));
  }

  public static Map<String, Object> view(Map<String, Object> t) {
    Object l = null;
    if (t.get("source") != null)
      l =
          map(
              "source",
              t.get("source"),
              "externalPlaceId",
              t.get("external_id"),
              "placeName",
              t.get("place_name"),
              "latitude",
              t.get("latitude").toString(),
              "longitude",
              t.get("longitude").toString(),
              "accuracyM",
              t.get("accuracy_m") == null ? null : t.get("accuracy_m").toString(),
              "capturedAt",
              instant(t.get("captured_at")),
              "lowAccuracyAccepted",
              t.get("low_accuracy_accepted"),
              "roadAddress",
              t.get("road_address"),
              "parcelAddress",
              t.get("parcel_address"),
              "regionCode",
              t.get("region_code"),
              "regionSido",
              t.get("region_sido"),
              "regionSigungu",
              t.get("region_sigungu"),
              "placeId",
              t.get("place_id"),
              "regionKey",
              t.get("region_key"));
    return map(
        "id",
        t.get("id"),
        "type",
        t.get("type"),
        "amount",
        t.get("amount").toString(),
        "currency",
        t.get("currency"),
        "category",
        map(
            "id",
            t.get("category_id"),
            "name",
            t.get("category_name"),
            "archived",
            t.get("category_archived") != null),
        "transactionDate",
        t.get("transaction_date").toString(),
        "memo",
        t.get("memo"),
        "location",
        l,
        "version",
        t.get("version"),
        "createdAt",
        instant(t.get("created_at")),
        "updatedAt",
        instant(t.get("updated_at")));
  }

  private static String instant(Object t) {
    return t == null ? null : ((java.sql.Timestamp) t).toInstant().toString();
  }
}
