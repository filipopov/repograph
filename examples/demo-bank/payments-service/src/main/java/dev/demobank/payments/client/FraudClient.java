package dev.demobank.payments.client;

import dev.demobank.contracts.FraudCheckRequest;
import dev.demobank.contracts.FraudCheckResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** HTTP client for fraud-service. */
@HttpExchange("/fraud")
public interface FraudClient {

  @PostExchange("/checks")
  FraudCheckResult check(@RequestBody FraudCheckRequest request);
}
