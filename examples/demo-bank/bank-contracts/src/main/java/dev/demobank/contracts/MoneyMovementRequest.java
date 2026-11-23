package dev.demobank.contracts;

import java.math.BigDecimal;

/** Debit or credit instruction sent to the accounts service. */
public record MoneyMovementRequest(BigDecimal amount, String currency, String reference) {
}
