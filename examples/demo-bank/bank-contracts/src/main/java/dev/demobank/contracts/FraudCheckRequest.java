package dev.demobank.contracts;

import java.math.BigDecimal;

public record FraudCheckRequest(
    String sourceAccountId,
    String targetAccountId,
    BigDecimal amount,
    String currency) {
}
