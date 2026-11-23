package dev.demobank.accounts;

import dev.demobank.contracts.MoneyMovementRequest;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class AccountService {

  private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

  private final AccountRepository repository;

  AccountService(AccountRepository repository) {
    this.repository = repository;
  }

  List<Account> findAll() {
    return List.copyOf(repository.findAll());
  }

  Account get(String id) {
    return repository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
  }

  Account debit(String id, MoneyMovementRequest request) {
    Account result = repository.update(id, account -> {
      checkCurrency(account, request.currency());
      BigDecimal newBalance = account.balance().subtract(request.amount());
      if (newBalance.signum() < 0) {
        throw new InsufficientFundsException(id);
      }
      return account.withBalance(newBalance);
    });
    LOG.info("Debited {} {} from {} (ref {})", request.amount(), request.currency(), id, request.reference());
    return result;
  }

  Account credit(String id, MoneyMovementRequest request) {
    Account result = repository.update(id, account -> {
      checkCurrency(account, request.currency());
      return account.withBalance(account.balance().add(request.amount()));
    });
    LOG.info("Credited {} {} to {} (ref {})", request.amount(), request.currency(), id, request.reference());
    return result;
  }

  private static void checkCurrency(Account account, String currency) {
    if (!account.currency().equals(currency)) {
      throw new CurrencyMismatchException(account.id(), account.currency(), currency);
    }
  }
}
