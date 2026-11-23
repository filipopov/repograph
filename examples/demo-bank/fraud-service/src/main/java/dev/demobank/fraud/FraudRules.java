package dev.demobank.fraud;

import dev.demobank.contracts.FraudCheckRequest;
import dev.demobank.contracts.FraudCheckResult;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Deliberately simple, deterministic rules so failures are easy to reason about in demos. */
@Component
class FraudRules {

  private final BigDecimal blockThreshold;
  private final BigDecimal reviewThreshold;

  FraudRules(
      @Value("${fraud.block-threshold:10000}") BigDecimal blockThreshold,
      @Value("${fraud.review-threshold:5000}") BigDecimal reviewThreshold) {
    this.blockThreshold = blockThreshold;
    this.reviewThreshold = reviewThreshold;
  }

  FraudCheckResult evaluate(FraudCheckRequest request) {
    if (request.sourceAccountId().equals(request.targetAccountId())) {
      return new FraudCheckResult(false, 100, "Source and target account are identical");
    }
    if (request.amount().compareTo(blockThreshold) > 0) {
      return new FraudCheckResult(false, 95, "Amount exceeds block threshold of " + blockThreshold);
    }
    if (request.amount().compareTo(reviewThreshold) > 0) {
      return new FraudCheckResult(true, 60, "High amount, approved with elevated risk");
    }
    return new FraudCheckResult(true, 10, "Low risk");
  }
}
