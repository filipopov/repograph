package dev.demobank.contracts;

import java.time.Instant;

public record NotificationDto(String id, String accountId, String message, Instant createdAt) {
}
