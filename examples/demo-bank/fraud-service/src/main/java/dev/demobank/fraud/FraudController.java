package dev.demobank.fraud;

import dev.demobank.contracts.FraudCheckRequest;
import dev.demobank.contracts.FraudCheckResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fraud")
class FraudController {

  private static final Logger LOG = LoggerFactory.getLogger(FraudController.class);

  private final FraudRules rules;

  FraudController(FraudRules rules) {
    this.rules = rules;
  }

  @PostMapping("/checks")
  FraudCheckResult check(@RequestBody FraudCheckRequest request) {
    FraudCheckResult result = rules.evaluate(request);
    LOG.info("Fraud check {} -> {}: approved={} score={}",
        request.sourceAccountId(), request.targetAccountId(), result.approved(), result.riskScore());
    return result;
  }
}
