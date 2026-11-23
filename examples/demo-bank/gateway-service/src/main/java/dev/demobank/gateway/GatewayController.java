package dev.demobank.gateway;

import dev.demobank.contracts.AccountDto;
import dev.demobank.contracts.Channel;
import dev.demobank.contracts.NotificationDto;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentRequest;
import dev.demobank.gateway.client.AccountsClient;
import dev.demobank.gateway.client.NotificationsClient;
import dev.demobank.gateway.client.PaymentsClient;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public API. Thin facade: no business logic, only routing to internal services. */
@RestController
@RequestMapping("/api/v1")
class GatewayController {

  private final AccountsClient accountsClient;
  private final PaymentsClient paymentsClient;
  private final NotificationsClient notificationsClient;

  GatewayController(AccountsClient accountsClient, PaymentsClient paymentsClient,
      NotificationsClient notificationsClient) {
    this.accountsClient = accountsClient;
    this.paymentsClient = paymentsClient;
    this.notificationsClient = notificationsClient;
  }

  @GetMapping("/accounts")
  List<AccountDto> accounts() {
    return accountsClient.list();
  }

  @GetMapping("/accounts/{id}")
  AccountDto account(@PathVariable String id) {
    return accountsClient.get(id);
  }

  @GetMapping("/accounts/{id}/notifications")
  List<NotificationDto> notifications(@PathVariable String id) {
    return notificationsClient.forAccount(id);
  }

  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.CREATED)
  PaymentDto createPayment(@RequestBody PaymentRequest request,
      @RequestHeader(value = "X-Channel", defaultValue = "WEB") Channel channel) {
    return paymentsClient.create(channel, request);
  }

  @GetMapping("/payments/{id}")
  PaymentDto payment(@PathVariable String id) {
    return paymentsClient.get(id);
  }
}
