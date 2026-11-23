package dev.demobank.contracts;

import java.math.BigDecimal;

public record AccountDto(String id, String owner, String currency, BigDecimal balance) {
}
