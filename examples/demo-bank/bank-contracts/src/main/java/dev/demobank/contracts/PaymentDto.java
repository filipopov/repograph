package dev.demobank.contracts;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentDto(
    String id,
    String sourceAccountId,
    String targetAccountId,
    BigDecimal amount,
    String currency,
    String reference,
    PaymentStatus status,
    String failureReason,
    Instant createdAt,
    Channel channel) {
}
