package com.moneymap.auth;

import com.moneymap.common.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Tokens {
  private final Secrets secrets;

  public Tokens(Secrets s) {
    secrets = s;
  }

  public String access(UUID user, UUID session) {
    try {
      Instant now = Instant.now();
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader(JWSAlgorithm.HS256),
              new JWTClaimsSet.Builder()
                  .subject(user.toString())
                  .claim("sid", session.toString())
                  .issuer("money-map")
                  .audience("money-map-api")
                  .issueTime(Date.from(now))
                  .expirationTime(Date.from(now.plusSeconds(300)))
                  .build());
      jwt.sign(new MACSigner(secrets.jwt));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException("Token generation failed");
    }
  }

  public record Identity(UUID user, UUID session) {}

  public Identity verify(String token, boolean allowExpired) {
    try {
      SignedJWT j = SignedJWT.parse(token);
      if (!JWSAlgorithm.HS256.equals(j.getHeader().getAlgorithm())
          || !j.verify(new MACVerifier(secrets.jwt))) throw new Exception();
      var c = j.getJWTClaimsSet();
      if (!"money-map".equals(c.getIssuer())
          || !c.getAudience().contains("money-map-api")
          || c.getExpirationTime() == null
          || c.getIssueTime() == null
          || c.getIssueTime().after(new Date(System.currentTimeMillis() + 30000)))
        throw new Exception();
      if (!allowExpired && !c.getExpirationTime().after(new Date()))
        throw new ApiException(401, "ACCESS_EXPIRED");
      return new Identity(
          UUID.fromString(c.getSubject()), UUID.fromString(c.getStringClaim("sid")));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(401, "UNAUTHENTICATED");
    }
  }

  public static String random() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException();
    }
  }
}
