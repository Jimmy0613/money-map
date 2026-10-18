package com.moneymap.ledger;

import static com.moneymap.common.Rules.*;

import com.moneymap.auth.Tokens;
import com.moneymap.common.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.util.MultiValueMap;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class Queries {
  private final LedgerRepository repo;
  private final Cursors cursors;

  public Queries(LedgerRepository r, Cursors c) {
    repo = r;
    cursors = c;
  }

  private record Selection(
      List<Map<String, Object>> rows,
      String revision,
      String hash,
      UUID user,
      int size,
      String cursor,
      String sql,
      Object[] args) {}

  private Selection select(
      UUID user,
      MultiValueMap<String, String> q,
      boolean expenses,
      boolean bounds,
      Set<String> allowed) {
    return select(user, q, expenses, bounds, allowed, true);
  }

  private Selection select(
      UUID user,
      MultiValueMap<String, String> q,
      boolean expenses,
      boolean bounds,
      Set<String> allowed,
      boolean fetchRows) {
    if (!allowed.containsAll(q.keySet())) throw ApiException.invalid();
    for (var e : q.entrySet())
      if (!e.getKey().equals("categoryIds") && e.getValue().size() != 1)
        throw ApiException.invalid();
    var owner = repo.user(user, false);
    LocalDate today = LocalDate.now(ZoneId.of((String) owner.get("timezone")));
    LocalDate from = today.withDayOfMonth(1), to = today.withDayOfMonth(today.lengthOfMonth());
    if (q.containsKey("from") != q.containsKey("to")) throw ApiException.invalid();
    if (q.containsKey("from")) {
      from = date(q.getFirst("from"));
      to = date(q.getFirst("to"));
      if (from.isAfter(to)) throw ApiException.invalid();
    }
    StringBuilder sql =
        new StringBuilder(
            LedgerRepository.SELECT + " WHERE t.user_id=? AND t.transaction_date BETWEEN ? AND ?");
    List<Object> args = new ArrayList<>(List.of(user, from, to));
    String type = expenses ? "EXPENSE" : q.getFirst("type");
    if (type != null) {
      Rules.type(type);
      sql.append(" AND t.type=?");
      args.add(type);
    }
    var ids = q.get("categoryIds");
    if (ids != null) {
      if (ids.isEmpty() || ids.size() > 100) throw ApiException.invalid();
      var unique = ids.stream().map(Rules::uuid).distinct().toList();
      String placeholders = String.join(",", Collections.nCopies(unique.size(), "?"));
      var categoryArgs = new ArrayList<Object>();
      categoryArgs.add(user);
      categoryArgs.addAll(unique);
      var categories =
          repo.query(
              "SELECT id,type FROM categories WHERE user_id=? AND id IN (" + placeholders + ")",
              categoryArgs.toArray());
      if (categories.size() != unique.size()) throw ApiException.missing();
      if (expenses && categories.stream().anyMatch(c -> !"EXPENSE".equals(c.get("type"))))
        throw ApiException.invalid();
      sql.append(" AND t.category_id IN (").append(placeholders).append(")");
      args.addAll(unique);
    }
    if (q.containsKey("hasLocation")) {
      String value = q.getFirst("hasLocation");
      if (!Set.of("true", "false").contains(value)) throw ApiException.invalid();
      sql.append(
          value.equals("true")
              ? " AND l.transaction_id IS NOT NULL"
              : " AND l.transaction_id IS NULL");
    }
    if (q.containsKey("placeId")) {
      UUID place = uuid(q.getFirst("placeId"));
      if (repo.query("SELECT id FROM places WHERE user_id=? AND id=?", user, place).isEmpty())
        throw ApiException.missing();
      sql.append(" AND l.place_id=?");
      args.add(place);
    }
    if (q.containsKey("regionKey")) {
      String key = q.getFirst("regionKey");
      if (!key.equals("UNKNOWN") && !key.matches("CODE:[0-9]{5}")) throw ApiException.invalid();
      sql.append(" AND l.region_key=?");
      args.add(key);
    }
    long count = List.of("south", "north", "west", "east").stream().filter(q::containsKey).count();
    if (bounds && count != 4 || count > 0 && count != 4) throw ApiException.invalid();
    if (count == 4) {
      var south = decimal(q.getFirst("south"), 6, BigDecimal.valueOf(90));
      var north = decimal(q.getFirst("north"), 6, BigDecimal.valueOf(90));
      var west = decimal(q.getFirst("west"), 6, BigDecimal.valueOf(180));
      var east = decimal(q.getFirst("east"), 6, BigDecimal.valueOf(180));
      if (south.compareTo(north) > 0) throw ApiException.invalid();
      sql.append(" AND l.latitude BETWEEN ? AND ? AND ")
          .append(
              west.compareTo(east) <= 0
                  ? "l.longitude BETWEEN ? AND ?"
                  : "(l.longitude>=? OR l.longitude<=?)");
      args.addAll(List.of(south, north, west, east));
    }
    int size = 20;
    try {
      if (q.containsKey("size")) size = Integer.parseInt(q.getFirst("size"));
    } catch (Exception e) {
      throw ApiException.invalid();
    }
    if (size < 1 || size > 100) throw ApiException.invalid();
    sql.append(" ORDER BY t.transaction_date DESC,t.created_at DESC,t.id DESC");
    var canonical = new TreeMap<String, List<String>>(q);
    canonical.remove("cursor");
    canonical.put("from", List.of(from.toString()));
    canonical.put("to", List.of(to.toString()));
    if (type != null) canonical.put("type", List.of(type));
    if (ids != null) canonical.put("categoryIds", ids.stream().distinct().sorted().toList());
    return new Selection(
        fetchRows ? repo.query(sql.toString(), args.toArray()) : List.of(),
        owner.get("data_revision").toString(),
        Tokens.hash(canonical.toString()),
        user,
        size,
        q.getFirst("cursor"),
        sql.toString(),
        args.toArray());
  }

  private Set<String> keys(String... keys) {
    return Set.of(keys);
  }

  public Map<String, Object> list(UUID user, MultiValueMap<String, String> q) {
    if (q.containsKey("sort") && !"date_desc".equals(q.getFirst("sort")))
      throw ApiException.invalid();
    var s =
        select(
            user,
            q,
            false,
            false,
            keys(
                "from",
                "to",
                "type",
                "categoryIds",
                "hasLocation",
                "placeId",
                "regionKey",
                "size",
                "cursor",
                "sort"),
            false);
    String sql = "SELECT * FROM (" + s.sql + ") selected";
    List<Object> args = new ArrayList<>(Arrays.asList(s.args));
    if (s.cursor != null) {
      String last = cursors.decode(s.cursor, s.user, s.revision, s.hash);
      String[] tuple = last.split("\\|");
      if (tuple.length != 3) throw new ApiException(400, "INVALID_CURSOR");
      try {
        args.add(date(tuple[0]));
        args.add(java.sql.Timestamp.valueOf(tuple[1]));
        args.add(uuid(tuple[2]));
      } catch (Exception e) {
        throw new ApiException(400, "INVALID_CURSOR");
      }
      sql += " WHERE (transaction_date,created_at,id)<(?,?,?)";
    }
    sql += " ORDER BY transaction_date DESC,created_at DESC,id DESC LIMIT ?";
    args.add(s.size + 1);
    var rows = repo.query(sql, args.toArray());
    boolean hasNext = rows.size() > s.size;
    var items = rows.subList(0, Math.min(s.size, rows.size()));
    String next = null;
    if (hasNext) {
      var r = items.getLast();
      next =
          cursors.encode(
              s.user,
              s.revision,
              s.hash,
              r.get("transaction_date") + "|" + r.get("created_at") + "|" + r.get("id"));
    }
    var totals =
        repo.query(
                "SELECT COALESCE(sum(amount) FILTER(WHERE type='INCOME'),0)"
                    + " income,COALESCE(sum(amount) FILTER(WHERE type='EXPENSE'),0)"
                    + " expense,count(*) count FROM ("
                    + s.sql
                    + ") selected",
                s.args)
            .getFirst();
    BigDecimal income = new BigDecimal(totals.get("income").toString()),
        expense = new BigDecimal(totals.get("expense").toString());
    return map(
        "items",
        items.stream().map(LedgerService::view).toList(),
        "page",
        map("nextCursor", next, "hasNext", hasNext, "size", s.size),
        "revision",
        s.revision,
        "totals",
        map(
            "incomeTotal",
            income.toPlainString(),
            "expenseTotal",
            expense.toPlainString(),
            "balance",
            income.subtract(expense).toPlainString(),
            "count",
            totals.get("count")));
  }

  private record Page(List<Map<String, Object>> items, Map<String, Object> page) {}

  private Page page(
      Selection s, List<Map<String, Object>> rows, Function<Map<String, Object>, String> tuple) {
    int start = 0;
    if (s.cursor != null) {
      String last = cursors.decode(s.cursor, s.user, s.revision, s.hash);
      int i = 0;
      while (i < rows.size() && !tuple.apply(rows.get(i)).equals(last)) i++;
      if (i == rows.size()) throw new ApiException(400, "INVALID_CURSOR");
      start = i + 1;
    }
    int end = Math.min(start + s.size, rows.size());
    var items = rows.subList(start, end);
    boolean next = end < rows.size();
    return new Page(
        items,
        map(
            "nextCursor",
            next ? cursors.encode(s.user, s.revision, s.hash, tuple.apply(items.getLast())) : null,
            "hasNext",
            next,
            "size",
            s.size));
  }

  public static BigDecimal sum(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(r -> new BigDecimal(r.get("amount").toString()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private Map<String, Object> summary(List<Map<String, Object>> rows, boolean list) {
    var income = rows.stream().filter(r -> "INCOME".equals(r.get("type"))).toList();
    var expense = rows.stream().filter(r -> "EXPENSE".equals(r.get("type"))).toList();
    var result =
        map(
            "incomeTotal",
            sum(income).toPlainString(),
            "expenseTotal",
            sum(expense).toPlainString(),
            "balance",
            sum(income).subtract(sum(expense)).toPlainString());
    if (list) result.put("count", rows.size());
    else {
      result.put("incomeCount", income.size());
      result.put("expenseCount", expense.size());
    }
    return result;
  }

  public Map<String, Object> statistics(UUID user, String kind, MultiValueMap<String, String> q) {
    if (kind.equals("monthly-comparison")) return monthly(user, q);
    Set<String> allowed =
        switch (kind) {
          case "summary" -> keys("from", "to");
          case "categories" ->
              keys("from", "to", "placeId", "regionKey", "south", "north", "west", "east");
          case "places" -> keys("from", "to", "categoryIds", "size", "cursor", "sort");
          case "regions" -> keys("from", "to", "categoryIds", "size", "cursor", "level");
          default -> throw ApiException.missing();
        };
    if (kind.equals("places")
        && q.containsKey("sort")
        && !Set.of("amount_desc", "count_desc").contains(q.getFirst("sort")))
      throw ApiException.invalid();
    if (kind.equals("regions") && q.containsKey("level") && !"SIGUNGU".equals(q.getFirst("level")))
      throw ApiException.invalid();
    var s = select(user, q, !kind.equals("summary"), false, allowed);
    Map<String, Object> result;
    if (kind.equals("summary")) result = summary(s.rows, false);
    else if (kind.equals("categories")) {
      var groups = group(s.rows, r -> r.get("category_id").toString());
      var items = new ArrayList<Map<String, Object>>();
      for (var e : groups.entrySet())
        items.add(
            map(
                "categoryId",
                e.getKey(),
                "name",
                e.getValue().getFirst().get("category_name"),
                "amount",
                sum(e.getValue()).toPlainString(),
                "count",
                e.getValue().size(),
                "percent",
                percent(sum(e.getValue()), sum(s.rows))));
      sort(items, "categoryId", false);
      result = map("items", items, "expenseTotal", sum(s.rows).toPlainString());
    } else if (kind.equals("places")) {
      var located = s.rows.stream().filter(r -> r.get("place_id") != null).toList();
      var groups = group(located, r -> r.get("place_id").toString());
      var items = new ArrayList<Map<String, Object>>();
      for (var e : groups.entrySet()) {
        var latest = e.getValue().getFirst();
        items.add(
            map(
                "placeId",
                e.getKey(),
                "name",
                title(latest),
                "address",
                address(latest),
                "amount",
                sum(e.getValue()).toPlainString(),
                "count",
                e.getValue().size(),
                "lastTransactionDate",
                latest.get("transaction_date").toString()));
      }
      sort(items, "placeId", "count_desc".equals(q.getFirst("sort")));
      var p = page(s, items, r -> r.get("amount") + "|" + r.get("count") + "|" + r.get("placeId"));
      result =
          map(
              "items",
              p.items,
              "identifiedExpenseTotal",
              sum(located).toPlainString(),
              "unidentifiedLocation",
              amountCount(
                  s.rows.stream()
                      .filter(r -> r.get("source") != null && r.get("place_id") == null)
                      .toList()),
              "locationless",
              amountCount(s.rows.stream().filter(r -> r.get("source") == null).toList()),
              "page",
              p.page);
    } else {
      var located = s.rows.stream().filter(r -> r.get("source") != null).toList();
      var groups = group(located, r -> r.get("region_key").toString());
      var names = regionNames();
      var items = new ArrayList<Map<String, Object>>();
      for (var e : groups.entrySet())
        items.add(
            map(
                "regionKey",
                e.getKey(),
                "name",
                regionName(e.getKey(), names),
                "amount",
                sum(e.getValue()).toPlainString(),
                "count",
                e.getValue().size(),
                "percent",
                percent(sum(e.getValue()), sum(located))));
      sort(items, "regionKey", false);
      var p = page(s, items, r -> r.get("amount") + "|" + r.get("regionKey"));
      result =
          map(
              "items",
              p.items,
              "locatedExpenseTotal",
              sum(located).toPlainString(),
              "locatedCount",
              located.size(),
              "unclassifiedCount",
              groups.getOrDefault("UNKNOWN", List.of()).size(),
              "locationlessCount",
              s.rows.size() - located.size(),
              "page",
              p.page);
    }
    result.put("revision", s.revision);
    return result;
  }

  private Map<String, String> regionNames() {
    var names = new HashMap<String, String>();
    for (var row : repo.query("SELECT region_key,sido_name,sigungu_name FROM region_dictionary"))
      names.put(
          (String) row.get("region_key"),
          (row.get("sido_name") + " " + row.get("sigungu_name")).strip());
    return names;
  }

  private String regionName(String key, Map<String, String> names) {
    return names.getOrDefault(key, "지역 미분류");
  }

  private Map<String, Object> amountCount(List<Map<String, Object>> rows) {
    return map("amount", sum(rows).toPlainString(), "count", rows.size());
  }

  private Map<String, List<Map<String, Object>>> group(
      List<Map<String, Object>> rows, Function<Map<String, Object>, String> key) {
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (var row : rows) groups.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(row);
    return groups;
  }

  private void sort(List<Map<String, Object>> items, String key, boolean count) {
    items.sort(
        (a, b) -> {
          int n =
              count
                  ? Integer.compare((Integer) b.get("count"), (Integer) a.get("count"))
                  : new BigDecimal(b.get("amount").toString())
                      .compareTo(new BigDecimal(a.get("amount").toString()));
          return n != 0 ? n : a.get(key).toString().compareTo(b.get(key).toString());
        });
  }

  private static String title(Map<String, Object> r) {
    return Objects.toString(
        r.get("place_name"),
        Objects.toString(address(r), r.get("latitude") + ", " + r.get("longitude")));
  }

  private static Object address(Map<String, Object> r) {
    return r.get("road_address") != null ? r.get("road_address") : r.get("parcel_address");
  }

  private String markerKey(Map<String, Object> r) {
    return r.get("place_id") != null
        ? "place:" + r.get("place_id")
        : "coord:" + r.get("latitude") + ":" + r.get("longitude");
  }

  public Map<String, Object> markers(UUID user, MultiValueMap<String, String> q, boolean details) {
    if (!details
        && (q.containsKey("markerKey")
            || q.containsKey("revision")
            || q.containsKey("cursor")
            || q.containsKey("size"))) throw ApiException.invalid();
    int level;
    try {
      level = Integer.parseInt(q.getFirst("level"));
    } catch (Exception e) {
      throw ApiException.invalid();
    }
    if (level < 1 || level > 14) throw ApiException.invalid();
    var s =
        select(
            user,
            q,
            true,
            true,
            keys(
                "from",
                "to",
                "categoryIds",
                "south",
                "north",
                "west",
                "east",
                "level",
                "markerKey",
                "revision",
                "cursor",
                "size"),
            false);
    String base =
        "WITH selected AS ("
            + s.sql
            + "), keyed AS (SELECT selected.*,CASE WHEN place_id IS NOT NULL THEN 'place:' ||"
            + " place_id::text ELSE 'coord:' || latitude::text || ':' || longitude::text END"
            + " marker_key FROM selected) ";
    if (details) {
      if (!s.revision.equals(q.getFirst("revision"))) throw new ApiException(409, "CURSOR_STALE");
      String key = q.getFirst("markerKey");
      if (key == null) throw ApiException.invalid();
      var args = new ArrayList<>(Arrays.asList(s.args));
      args.add(key);
      var totals =
          repo.query(
                  base + "SELECT sum(amount) amount,count(*) count FROM keyed WHERE marker_key=?",
                  args.toArray())
              .getFirst();
      if (((Number) totals.get("count")).longValue() == 0) throw ApiException.missing();
      var latest =
          repo.query(
                  base
                      + "SELECT * FROM keyed WHERE marker_key=? ORDER BY transaction_date"
                      + " DESC,created_at DESC,id DESC LIMIT 1",
                  args.toArray())
              .getFirst();
      String paging = "SELECT * FROM keyed WHERE marker_key=?";
      if (s.cursor != null) {
        String[] last = cursors.decode(s.cursor, user, s.revision, s.hash).split("\\|");
        if (last.length != 3) throw new ApiException(400, "INVALID_CURSOR");
        try {
          args.add(date(last[0]));
          args.add(java.sql.Timestamp.valueOf(last[1]));
          args.add(uuid(last[2]));
        } catch (Exception e) {
          throw new ApiException(400, "INVALID_CURSOR");
        }
        paging += " AND (transaction_date,created_at,id)<(?,?,?)";
      }
      args.add(s.size + 1);
      var rows =
          repo.query(
              base + paging + " ORDER BY transaction_date DESC,created_at DESC,id DESC LIMIT ?",
              args.toArray());
      boolean more = rows.size() > s.size;
      var items = rows.subList(0, Math.min(s.size, rows.size()));
      String next = null;
      if (more) {
        var last = items.getLast();
        next =
            cursors.encode(
                user,
                s.revision,
                s.hash,
                last.get("transaction_date") + "|" + last.get("created_at") + "|" + last.get("id"));
      }
      return map(
          "markerKey",
          key,
          "title",
          title(latest),
          "address",
          address(latest),
          "amount",
          totals.get("amount").toString(),
          "count",
          totals.get("count"),
          "items",
          items.stream().map(LedgerService::view).toList(),
          "page",
          map("nextCursor", next, "hasNext", more, "size", s.size),
          "revision",
          s.revision);
    }
    int limit = level <= 5 ? 500 : level <= 9 ? 300 : 200;
    String grouped =
        base
            + ", ranked AS (SELECT keyed.*,row_number() OVER(PARTITION BY marker_key ORDER BY"
            + " transaction_date DESC,created_at DESC,id DESC) rn,sum(amount) OVER(PARTITION BY"
            + " marker_key) total,count(*) OVER(PARTITION BY marker_key) quantity FROM keyed)"
            + " SELECT * FROM ranked WHERE rn=1 ORDER BY marker_key LIMIT ?";
    var args = new ArrayList<>(Arrays.asList(s.args));
    args.add(limit + 1);
    var rows = repo.query(grouped, args.toArray());
    boolean narrow = rows.size() > limit;
    var items = new ArrayList<Map<String, Object>>();
    if (!narrow)
      for (var r : rows)
        items.add(
            map(
                "markerKey",
                r.get("marker_key"),
                "title",
                title(r),
                "latitude",
                r.get("latitude").toString(),
                "longitude",
                r.get("longitude").toString(),
                "amount",
                r.get("total").toString(),
                "count",
                r.get("quantity")));
    var viewport =
        repo.query(
                base + "SELECT COALESCE(sum(amount),0) amount,count(*) count FROM selected", s.args)
            .getFirst();
    var period = new org.springframework.util.LinkedMultiValueMap<String, String>(q);
    List.of("south", "north", "west", "east", "level").forEach(period::remove);
    var all = select(user, period, true, false, keys("from", "to", "categoryIds"), false);
    var total =
        repo.query(
                "SELECT COALESCE(sum(amount),0) amount,count(*) FILTER(WHERE source IS NULL)"
                    + " locationless FROM ("
                    + all.sql
                    + ") selected",
                all.args)
            .getFirst();
    return map(
        "items",
        items,
        "viewportExpenseTotal",
        viewport.get("amount").toString(),
        "viewportCount",
        viewport.get("count"),
        "periodExpenseTotal",
        total.get("amount").toString(),
        "locationlessCount",
        total.get("locationless"),
        "requiresNarrowing",
        narrow,
        "limit",
        limit,
        "revision",
        s.revision);
  }

  private Map<String, Object> monthly(UUID user, MultiValueMap<String, String> q) {
    if (!keys("month", "categoryIds").containsAll(q.keySet())) throw ApiException.invalid();
    YearMonth month;
    try {
      String m = q.getFirst("month");
      if (m == null || !m.matches("[0-9]{4}-[0-9]{2}")) throw ApiException.invalid();
      month = YearMonth.parse(m);
    } catch (DateTimeException e) {
      throw ApiException.invalid();
    }
    var filter = new org.springframework.util.LinkedMultiValueMap<String, String>();
    if (q.containsKey("categoryIds")) filter.put("categoryIds", q.get("categoryIds"));
    filter.set("from", month.minusMonths(1).atDay(1).toString());
    filter.set("to", month.atEndOfMonth().toString());
    var s = select(user, filter, true, false, keys("from", "to", "categoryIds"));
    var current =
        s.rows.stream()
            .filter(
                r ->
                    !((java.sql.Date) r.get("transaction_date"))
                        .toLocalDate()
                        .isBefore(month.atDay(1)))
            .toList();
    var previous =
        s.rows.stream()
            .filter(
                r ->
                    ((java.sql.Date) r.get("transaction_date"))
                        .toLocalDate()
                        .isBefore(month.atDay(1)))
            .toList();
    var total = comparison(sum(current), sum(previous));
    total.put("currentCount", current.size());
    total.put("previousCount", previous.size());
    return map(
        "month",
        month.toString(),
        "previousMonth",
        month.minusMonths(1).toString(),
        "total",
        total,
        "categories",
        compareGroups(current, previous, false),
        "regions",
        compareGroups(current, previous, true),
        "revision",
        s.revision);
  }

  private List<Map<String, Object>> compareGroups(
      List<Map<String, Object>> current, List<Map<String, Object>> previous, boolean regions) {
    Function<Map<String, Object>, String> key =
        r -> r.get(regions ? "region_key" : "category_id").toString();
    if (regions) {
      current = current.stream().filter(r -> r.get("source") != null).toList();
      previous = previous.stream().filter(r -> r.get("source") != null).toList();
    }
    var names = regions ? regionNames() : Map.<String, String>of();
    var a = group(current, key);
    var b = group(previous, key);
    var union = new TreeSet<>(a.keySet());
    union.addAll(b.keySet());
    var result = new ArrayList<Map<String, Object>>();
    for (String k : union) {
      var x = a.getOrDefault(k, List.of());
      var y = b.getOrDefault(k, List.of());
      var v = comparison(sum(x), sum(y));
      v.put("key", k);
      v.put(
          "name",
          regions ? regionName(k, names) : (!x.isEmpty() ? x : y).getFirst().get("category_name"));
      result.add(v);
    }
    if (regions) {
      result.sort(
          (x, y) -> {
            int c =
                new BigDecimal(y.get("difference").toString())
                    .abs()
                    .compareTo(new BigDecimal(x.get("difference").toString()).abs());
            return c != 0 ? c : x.get("key").toString().compareTo(y.get("key").toString());
          });
      return result.stream().limit(10).toList();
    }
    return result;
  }

  public static Map<String, Object> comparison(BigDecimal current, BigDecimal previous) {
    BigDecimal delta = current.subtract(previous);
    return map(
        "current",
        current.toPlainString(),
        "previous",
        previous.toPlainString(),
        "difference",
        delta.toPlainString(),
        "growthPercent",
        previous.signum() == 0
            ? null
            : delta
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, java.math.RoundingMode.HALF_UP),
        "status",
        previous.signum() == 0 ? "NOT_COMPARABLE" : "COMPARABLE");
  }
}
