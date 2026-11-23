package dev.demobank.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
class InsufficientFundsException extends RuntimeException {

  InsufficientFundsException(String accountId) {
    super("Insufficient funds on account " + accountId);
  }
}
