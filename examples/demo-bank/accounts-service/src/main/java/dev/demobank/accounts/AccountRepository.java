package dev.demobank.accounts;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Repository;

/** In-memory store seeded with demo accounts. */
@Repository
class AccountRepository {

  private final Map<String, Account> accounts = new ConcurrentHashMap<>();

  AccountRepository() {
    save(new Account("acc-alice", "Alice", "CHF", new BigDecimal("12000.00")));
    save(new Account("acc-bob", "Bob", "CHF", new BigDecimal("500.00")));
    save(new Account("acc-carol", "Carol", "EUR", new BigDecimal("2000.00")));
  }

  Optional<Account> findById(String id) {
    return Optional.ofNullable(accounts.get(id));
  }

  Collection<Account> findAll() {
    return accounts.values();
  }

  void save(Account account) {
    accounts.put(account.id(), account);
  }

  /** Atomically applies {@code update} to the account; the operator may throw to abort. */
  Account update(String id, UnaryOperator<Account> update) {
    Account updated = accounts.computeIfPresent(id, (key, account) -> update.apply(account));
    if (updated == null) {
      throw new AccountNotFoundException(id);
    }
    return updated;
  }
}
