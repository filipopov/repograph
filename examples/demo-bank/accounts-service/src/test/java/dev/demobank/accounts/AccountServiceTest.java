package dev.demobank.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.demobank.contracts.MoneyMovementRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountServiceTest {

  private AccountService service;

  @BeforeEach
  void setUp() {
    service = new AccountService(new AccountRepository());
  }

  @Test
  void debitReducesBalance() {
    Account result = service.debit("acc-bob", movement("100.00", "CHF"));

    assertThat(result.balance()).isEqualByComparingTo("400.00");
  }

  @Test
  void creditIncreasesBalance() {
    Account result = service.credit("acc-bob", movement("50.00", "CHF"));

    assertThat(result.balance()).isEqualByComparingTo("550.00");
  }

  @Test
  void debitBeyondBalanceIsRejectedAndBalanceUnchanged() {
    assertThatThrownBy(() -> service.debit("acc-bob", movement("500.01", "CHF")))
        .isInstanceOf(InsufficientFundsException.class);

    assertThat(service.get("acc-bob").balance()).isEqualByComparingTo("500.00");
  }

  @Test
  void currencyMismatchIsRejected() {
    assertThatThrownBy(() -> service.credit("acc-carol", movement("10.00", "CHF")))
        .isInstanceOf(CurrencyMismatchException.class);
  }

  @Test
  void unknownAccountIsRejected() {
    assertThatThrownBy(() -> service.debit("acc-nobody", movement("1.00", "CHF")))
        .isInstanceOf(AccountNotFoundException.class);
  }

  private static MoneyMovementRequest movement(String amount, String currency) {
    return new MoneyMovementRequest(new BigDecimal(amount), currency, "test");
  }
}
