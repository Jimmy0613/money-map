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

  public AccountAccessPolicy(Environment env) {
    restricted = env.getProperty("app.access.restricted", Boolean.class, false);
    signupEnabled = env.getProperty("app.access.signup-enabled", Boolean.class, true);
    emails = Arrays.stream(env.getProperty("app.access.allowed-emails", "").split(","))
        .map(AccountAccessPolicy::normalize).filter(s -> !s.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  public boolean allows(String email) {
    return email != null && (!restricted || emails.contains(normalize(email)));
  }

  public boolean allowsSignup(String email) {
    return signupEnabled && allows(email);
  }

  private static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
