package com.moneymap.auth;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Personal deployment access policy. An enabled, empty list denies everyone. */
@Component
public final class AccountAccessPolicy {
  private final boolean restricted;
  private final boolean signupEnabled;
  private final Set<String> emails;
  private final Set<String> demoEmails;
  private final int demoCreates, demoCategories, demoSessions, demoTokens;

  public AccountAccessPolicy(Environment env) {
    restricted = env.getProperty("app.access.restricted", Boolean.class, false);
    signupEnabled = env.getProperty("app.access.signup-enabled", Boolean.class, true);
    emails = Arrays.stream(env.getProperty("app.access.allowed-emails", "").split(","))
        .map(AccountAccessPolicy::normalize).filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
    demoEmails = Arrays.stream(env.getProperty("app.demo.emails", "").split(","))
        .map(AccountAccessPolicy::normalize).filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
    demoCreates = env.getProperty("app.demo.max-created-transactions", Integer.class, 200);
    demoCategories = env.getProperty("app.demo.max-categories", Integer.class, 40);
    demoSessions = env.getProperty("app.demo.max-sessions", Integer.class, 10);
    demoTokens = env.getProperty("app.demo.max-session-tokens", Integer.class, 60);
    if (demoCreates < 1 || demoCategories < 16 || demoSessions < 1 || demoTokens < 1)
      throw new IllegalArgumentException("Invalid demo quotas");
  }

  public boolean allows(String email) {
    return email != null && (!restricted || emails.contains(normalize(email)));
  }

  public boolean allowsSignup(String email) {
    return signupEnabled && allows(email);
  }

  public boolean isDemo(String email) {
    return email != null && demoEmails.contains(normalize(email));
  }

  public int demoCreates() { return demoCreates; }
  public int demoCategories() { return demoCategories; }
  public int demoSessions() { return demoSessions; }
  public int demoTokens() { return demoTokens; }

  private static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
