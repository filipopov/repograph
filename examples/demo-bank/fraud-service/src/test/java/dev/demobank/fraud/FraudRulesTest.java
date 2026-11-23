package dev.demobank.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import dev.demobank.contracts.FraudCheckRequest;
import dev.demobank.contracts.FraudCheckResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FraudRulesTest {

  private final FraudRules rules = new FraudRules(new BigDecimal("10000"), new BigDecimal("5000"));

  @Test
  void lowAmountIsApproved() {
    FraudCheckResult result = rules.evaluate(request("a", "b", "100"));

    assertThat(result.approved()).isTrue();
    assertThat(result.riskScore()).isEqualTo(10);
  }

  @Test
  void highAmountIsApprovedWithElevatedRisk() {
    FraudCheckResult result = rules.evaluate(request("a", "b", "7500"));

    assertThat(result.approved()).isTrue();
    assertThat(result.riskScore()).isEqualTo(60);
  }

  @Test
  void amountAboveBlockThresholdIsRejected() {
    assertThat(rules.evaluate(request("a", "b", "10000.01")).approved()).isFalse();
  }

  @Test
  void selfTransferIsRejected() {
    assertThat(rules.evaluate(request("a", "a", "1")).approved()).isFalse();
  }

  private static FraudCheckRequest request(String source, String target, String amount) {
    return new FraudCheckRequest(source, target, new BigDecimal(amount), "CHF");
  }
}
