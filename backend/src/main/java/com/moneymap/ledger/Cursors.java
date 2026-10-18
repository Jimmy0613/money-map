package com.moneymap.ledger;

import static com.moneymap.common.Rules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.common.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class Cursors {
  private final Secrets secrets;
  private final ObjectMapper json;

  public Cursors(Secrets s, ObjectMapper j) {
    secrets = s;
    json = j;
  }

  private byte[] sign(String value) throws Exception {
    Mac m = Mac.getInstance("HmacSHA256");
    m.init(new SecretKeySpec(secrets.cursor, "HmacSHA256"));
    return m.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  public String encode(UUID user, String revision, String filter, String tuple) {
    try {
      String b =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  json.writeValueAsBytes(
                      map(
                          "user",
                          user.toString(),
                          "revision",
                          revision,
                          "filter",
                          filter,
                          "tuple",
                          tuple,
                          "expires",
                          System.currentTimeMillis() + 900000)));
      return b + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(b));
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }

  public String decode(String cursor, UUID user, String revision, String filter) {
    try {
      if (cursor.length() > 4096) throw new Exception();
      String[] p = cursor.split("\\.");
      if (p.length != 2 || !MessageDigest.isEqual(sign(p[0]), Base64.getUrlDecoder().decode(p[1])))
        throw new Exception();
      var node = json.readTree(Base64.getUrlDecoder().decode(p[0]));
      if (!node.path("user").asText().equals(user.toString())
          || !node.path("filter").asText().equals(filter)
          || node.path("expires").asLong() < System.currentTimeMillis()) throw new Exception();
      if (!node.path("revision").asText().equals(revision))
        throw new ApiException(409, "CURSOR_STALE");
      return node.path("tuple").asText();
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(400, "INVALID_CURSOR");
    }
  }
}
