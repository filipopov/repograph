package dev.demobank.accounts;

import java.math.BigDecimal;

record Account(String id, String owner, String currency, BigDecimal balance) {

  Account withBalance(BigDecimal newBalance) {
    return new Account(id, owner, currency, newBalance);
  }
}
