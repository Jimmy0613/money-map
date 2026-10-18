package com.moneymap.auth;

import static com.moneymap.common.Rules.*;

import com.moneymap.common.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final JdbcTemplate db;
  private final Tokens tokens;
  private final RateLimits limits;
  private final AccountAccessPolicy access;
  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
  private final String dummyHash = encoder.encode(Tokens.random());

  public AuthService(JdbcTemplate db, Tokens tokens, RateLimits limits, AccountAccessPolicy access) {
    this.db = db;
    this.tokens = tokens;
    this.limits = limits;
    this.access = access;
  }

  public record Signup(
      @NotBlank @Email @Size(max = 254) String email,
      @NotNull @Size(min = 10, max = 64) String password,
      @NotNull String passwordConfirmation,
      @NotNull Boolean termsAccepted,
      @NotNull String termsVersion) {
    public Signup {
      if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
    }
  }

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotNull @Size(max = 64) String password) {
    public Login {
      if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
    }
  }

  public record DeleteAccount(@NotNull String currentPassword, @NotNull String confirmation) {}

  public record Credentials(UUID user, UUID session, String access, String refresh) {}

  @Transactional
  public Map<String, Object> signup(Signup s, String ip) {
    limits.hit("signup:" + ip, 10, 3600);
    if (!access.allowsSignup(s.email)) throw new ApiException(403, "REGISTRATION_CLOSED");
    if (!s.password.matches("(?s)(?=.*[A-Za-z])(?=.*[0-9]).{10,64}"))
      throw ApiException.invalid("password", "INVALID_VALUE", "영문·숫자 포함 10~64자로 입력하세요.");
    if (!s.password.equals(s.passwordConfirmation))
      throw ApiException.invalid("passwordConfirmation", "MISMATCH", "비밀번호가 일치하지 않습니다.");
    if (!Boolean.TRUE.equals(s.termsAccepted))
      throw ApiException.invalid("termsAccepted", "REQUIRED", "이용 안내 동의가 필요합니다.");
    if (!"v1".equals(s.termsVersion))
      throw ApiException.invalid("termsVersion", "INVALID_VALUE", "현재 이용 안내를 확인하세요.");
    UUID id = UUID.randomUUID();
    String email = s.email.strip().toLowerCase(Locale.ROOT);
    int count =
        db.update(
            "INSERT INTO users(id,email,password_hash,terms_version) VALUES (?,?,?,?) ON"
                + " CONFLICT(email) DO NOTHING",
            id,
            email,
            encoder.encode(s.password),
            s.termsVersion);
    if (count == 0) throw new ApiException(409, "EMAIL_EXISTS");
    defaults(
        id,
        "EXPENSE",
        new String[] {"식비", "카페", "교통", "쇼핑", "주거", "생활", "의료", "문화", "여행", "교육", "기타"},
        new String[] {
          "food",
          "cafe",
          "transport",
          "shopping",
          "home",
          "life",
          "medical",
          "culture",
          "travel",
          "education",
          "other"
        });
    defaults(
        id,
        "INCOME",
        new String[] {"급여", "용돈", "사업", "금융", "기타"},
        new String[] {"salary", "money", "business", "finance", "other"});
    return map("id", id, "email", email, "timezone", "Asia/Seoul");
  }

  private void defaults(UUID user, String type, String[] names, String[] icons) {
    for (int i = 0; i < names.length; i++)
      db.update(
          "INSERT INTO categories(id,user_id,type,name,normalized_name,is_default,icon,sort_order)"
              + " VALUES (?,?,?,?,?,true,?,?)",
          UUID.randomUUID(),
          user,
          type,
          names[i],
          names[i],
          icons[i],
          i);
  }

  @Transactional
  public Credentials login(Login l, String ip) {
    limits.hit("login:" + ip, 30, 60);
    String email = l.email.strip().toLowerCase(Locale.ROOT);
    String key = "email:" + Tokens.hash(email);
    limits.hit(key, 5, 900);
    var users =
        db.queryForList("SELECT id,password_hash FROM users WHERE email=? FOR UPDATE", email);
    String h = users.isEmpty() ? dummyHash : (String) users.getFirst().get("password_hash");
    boolean valid = encoder.matches(l.password, h);
    if (users.isEmpty() || !valid || !access.allows(email)) throw new ApiException(401, "UNAUTHENTICATED");
    limits.clear(key);
    UUID user = (UUID) users.getFirst().get("id"), sid = UUID.randomUUID();
    db.update(
        "INSERT INTO auth_sessions(id,user_id,expires_at) VALUES (?,?,now()+interval '14 days')",
        sid,
        user);
    return issue(user, sid);
  }

  private Credentials issue(UUID user, UUID sid) {
    String refresh = Tokens.random();
    db.update(
        "INSERT INTO refresh_tokens(id,session_id,token_hash) VALUES (?,?,?)",
        UUID.randomUUID(),
        sid,
        Tokens.hash(refresh));
    return new Credentials(user, sid, tokens.access(user, sid), refresh);
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Credentials refresh(String raw) {
    if (raw == null) throw new ApiException(401, "UNAUTHENTICATED");
    var rows =
        db.queryForList(
            "SELECT s.id,s.user_id,s.revoked_at,s.expires_at,u.email FROM auth_sessions s JOIN users u ON u.id=s.user_id JOIN"
                + " refresh_tokens t ON t.session_id=s.id WHERE t.token_hash=? FOR UPDATE OF s",
            Tokens.hash(raw));
    if (rows.isEmpty()) throw new ApiException(401, "UNAUTHENTICATED");
    var s = rows.getFirst();
    UUID sid = (UUID) s.get("id");
    limits.hit("refresh:" + sid, 30, 60);
    if (!access.allows((String) s.get("email"))
        || s.get("revoked_at") != null
        || !((java.sql.Timestamp) s.get("expires_at")).toInstant().isAfter(Instant.now()))
      throw new ApiException(401, "UNAUTHENTICATED");
    var token =
        db.queryForMap(
            "SELECT consumed_at FROM refresh_tokens WHERE token_hash=?", Tokens.hash(raw));
    if (token.get("consumed_at") != null) {
      db.update("UPDATE auth_sessions SET revoked_at=now() WHERE id=?", sid);
      throw new ApiException(401, "REFRESH_REUSED");
    }
    db.update("UPDATE refresh_tokens SET consumed_at=now() WHERE token_hash=?", Tokens.hash(raw));
    return issue((UUID) s.get("user_id"), sid);
  }

  public Tokens.Identity authenticate(String raw) {
    if (raw == null) throw new ApiException(401, "UNAUTHENTICATED");
    var id = tokens.verify(raw, false);
    var rows = db.queryForList(
        "SELECT u.email FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND"
            + " s.user_id=? AND s.revoked_at IS NULL AND s.expires_at>now()",
        id.session(), id.user());
    if (rows.size() != 1 || !access.allows((String) rows.getFirst().get("email")))
      throw new ApiException(401, "UNAUTHENTICATED");
    return id;
  }

  @Transactional
  public void logout(String access, String refresh) {
    if (refresh != null)
      db.update(
          "UPDATE auth_sessions SET revoked_at=now() WHERE id IN (SELECT session_id FROM"
              + " refresh_tokens WHERE token_hash=?)",
          Tokens.hash(refresh));
    if (access != null)
      try {
        var i = tokens.verify(access, true);
        db.update(
            "UPDATE auth_sessions SET revoked_at=now() WHERE id=? AND user_id=?",
            i.session(),
            i.user());
      } catch (ApiException ignored) {
      }
  }

  public Map<String, Object> me(UUID id) {
    var rows =
        db.queryForList("SELECT id,email,display_name,timezone,version FROM users WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(401, "UNAUTHENTICATED");
    var u = rows.getFirst();
    return map(
        "id",
        id,
        "email",
        u.get("email"),
        "displayName",
        u.get("display_name"),
        "timezone",
        u.get("timezone"),
        "today",
        LocalDate.now(ZoneId.of((String) u.get("timezone"))).toString(),
        "version",
        u.get("version"));
  }

  @Transactional
  public void delete(UUID user, DeleteAccount d) {
    var rows = db.queryForList("SELECT password_hash FROM users WHERE id=? FOR UPDATE", user);
    if (rows.isEmpty()) throw new ApiException(401, "UNAUTHENTICATED");
    if (!"DELETE".equals(d.confirmation)) throw ApiException.invalid();
    if (!encoder.matches(d.currentPassword, (String) rows.getFirst().get("password_hash")))
      throw new ApiException(401, "UNAUTHENTICATED");
    db.update("INSERT INTO privacy.deletion_ledger(user_id) VALUES (?)", user);
    db.update("DELETE FROM users WHERE id=?", user);
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  @Transactional
  public void expireSessions() {
    db.update("DELETE FROM auth_sessions WHERE expires_at<now()");
  }
}
