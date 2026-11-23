package dev.demobank.contracts;

import java.math.BigDecimal;
import java.time.Instant;

/** Published on {@link Topics#PAYMENTS_COMPLETED} after money has moved. */
public record PaymentCompletedEvent(
    String paymentId,
    String sourceAccountId,
    String targetAccountId,
    BigDecimal amount,
    String currency,
    Instant completedAt,
    Channel channel) {
}
