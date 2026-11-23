package dev.demobank.gateway.client;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** HTTP client for payments-service. */
@HttpExchange("/payments")
public interface PaymentsClient {

  @PostExchange
  PaymentDto create(@RequestHeader("X-Channel") Channel channel, @RequestBody PaymentRequest request);

  @GetExchange("/{id}")
  PaymentDto get(@PathVariable String id);
}
