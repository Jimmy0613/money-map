package com.moneymap;

import static org.junit.jupiter.api.Assertions.*;
import com.moneymap.auth.AccountAccessPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AccountAccessPolicyTest {
  @Test void closedSignupDoesNotPreventExistingAllowedAccountsFromLoggingIn() {
    var policy = new AccountAccessPolicy(new MockEnvironment()
        .withProperty("app.access.restricted", "true")
        .withProperty("app.access.allowed-emails", "owner@example.test")
        .withProperty("app.access.signup-enabled", "false"));
    assertFalse(policy.allowsSignup("owner@example.test"));
    assertTrue(policy.allows("owner@example.test"));
  }
  @Test void existingUnrestrictedDeploymentKeepsItsBehavior() {
    assertTrue(new AccountAccessPolicy(new MockEnvironment()).allows("other@example.test"));
  }

  @Test void restrictedEmptyListFailsClosed() {
    var policy = new AccountAccessPolicy(new MockEnvironment()
        .withProperty("app.access.restricted", "true"));
    assertFalse(policy.allows("any@example.test"));
    assertFalse(policy.allows(null));
  }

  @Test void exactNormalizedMembershipDoesNotPermitSubstringsOrDomains() {
    var policy = new AccountAccessPolicy(new MockEnvironment()
        .withProperty("app.access.restricted", "true")
        .withProperty("app.access.allowed-emails", " Owner@Example.test , demo@example.test,"));
    assertTrue(policy.allows(" OWNER@example.test "));
    assertTrue(policy.allows("demo@example.test"));
    assertFalse(policy.allows("other@example.test"));
    assertFalse(policy.allows("owner@example.test.attacker.test"));
    assertFalse(policy.allows(""));
  }
}
