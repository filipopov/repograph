package dev.demobank.contracts;

import java.math.BigDecimal;

public record PaymentRequest(
    String sourceAccountId,
    String targetAccountId,
    BigDecimal amount,
    String currency,
    String reference) {
}
