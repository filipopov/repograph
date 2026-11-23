package dev.demobank.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
class CurrencyMismatchException extends RuntimeException {

  CurrencyMismatchException(String accountId, String expected, String actual) {
    super("Account " + accountId + " is in " + expected + ", got " + actual);
  }
}
