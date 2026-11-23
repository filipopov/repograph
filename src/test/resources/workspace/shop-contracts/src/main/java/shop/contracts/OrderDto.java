package shop.contracts;

import java.math.BigDecimal;

public record OrderDto(String id, BigDecimal amount, String customerId) {
}
