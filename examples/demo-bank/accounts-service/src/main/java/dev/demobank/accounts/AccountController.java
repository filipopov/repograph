package dev.demobank.accounts;

import dev.demobank.contracts.AccountDto;
import dev.demobank.contracts.MoneyMovementRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountService service;

  AccountController(AccountService service) {
    this.service = service;
  }

  @GetMapping
  List<AccountDto> list() {
    return service.findAll().stream().map(AccountController::toDto).toList();
  }

  @GetMapping("/{id}")
  AccountDto get(@PathVariable String id) {
    return toDto(service.get(id));
  }

  @PostMapping("/{id}/debit")
  AccountDto debit(@PathVariable String id, @RequestBody MoneyMovementRequest request) {
    return toDto(service.debit(id, request));
  }

  @PostMapping("/{id}/credit")
  AccountDto credit(@PathVariable String id, @RequestBody MoneyMovementRequest request) {
    return toDto(service.credit(id, request));
  }

  private static AccountDto toDto(Account account) {
    return new AccountDto(account.id(), account.owner(), account.currency(), account.balance());
  }
}
