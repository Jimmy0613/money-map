package com.moneymap.common;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class Secrets {
  public final byte[] jwt, cursor;
  public final String origin;
  public final boolean secure, recoveryRequired, local;

  public Secrets(
      @Value("${app.jwt-secret}") String j,
      @Value("${app.cursor-secret}") String c,
      @Value("${app.origin}") String o,
      @Value("${app.cookie-secure}") boolean s,
      Environment env) {
    recoveryRequired = env.getProperty("app.recovery-required", Boolean.class, false);
    jwt = decode(j);
    cursor = decode(c);
    if (Arrays.equals(jwt, cursor))
      throw new IllegalStateException("Distinct signing secrets required");
    origin = o;
    secure = s;
    local = Arrays.asList(env.getActiveProfiles()).contains("local");
    if (local && !o.matches("http://localhost(:[0-9]+)?"))
      throw new IllegalStateException("Local profile requires a localhost origin");
    if (!s && !(local && o.matches("http://localhost(:[0-9]+)?")))
      throw new IllegalStateException("Secure cookies required outside localhost local profile");
    if (!local && !o.startsWith("https://"))
      throw new IllegalStateException("HTTPS origin required");
  }

  private byte[] decode(String s) {
    try {
      byte[] b = Base64.getDecoder().decode(s);
      if (b.length < 32) throw new IllegalArgumentException();
      return b;
    } catch (Exception e) {
      throw new IllegalStateException("Valid 256-bit signing secret required");
    }
  }
}
