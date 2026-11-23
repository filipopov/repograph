package dev.demobank.contracts;

public record FraudCheckResult(boolean approved, int riskScore, String reason) {
}
