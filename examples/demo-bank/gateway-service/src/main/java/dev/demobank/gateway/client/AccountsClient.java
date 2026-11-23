package dev.demobank.gateway.client;

import dev.demobank.contracts.AccountDto;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** HTTP client for accounts-service. */
@HttpExchange("/accounts")
public interface AccountsClient {

  @GetExchange
  List<AccountDto> list();

  @GetExchange("/{id}")
  AccountDto get(@PathVariable String id);
}
