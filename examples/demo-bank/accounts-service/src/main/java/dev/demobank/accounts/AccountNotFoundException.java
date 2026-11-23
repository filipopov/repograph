package dev.demobank.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class AccountNotFoundException extends RuntimeException {

  AccountNotFoundException(String accountId) {
    super("Account not found: " + accountId);
  }
}
