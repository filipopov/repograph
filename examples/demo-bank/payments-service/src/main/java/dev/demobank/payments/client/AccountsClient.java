package dev.demobank.payments.client;

import dev.demobank.contracts.AccountDto;
import dev.demobank.contracts.MoneyMovementRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** HTTP client for accounts-service. */
@HttpExchange("/accounts")
public interface AccountsClient {

  @GetExchange("/{id}")
  AccountDto get(@PathVariable String id);

  @PostExchange("/{id}/debit")
  AccountDto debit(@PathVariable String id, @RequestBody MoneyMovementRequest request);

  @PostExchange("/{id}/credit")
  AccountDto credit(@PathVariable String id, @RequestBody MoneyMovementRequest request);
}
