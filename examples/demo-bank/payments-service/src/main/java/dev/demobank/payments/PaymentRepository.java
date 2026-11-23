package dev.demobank.payments;

import dev.demobank.contracts.PaymentDto;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
class PaymentRepository {

  private final Map<String, PaymentDto> payments = new ConcurrentHashMap<>();

  PaymentDto save(PaymentDto payment) {
    payments.put(payment.id(), payment);
    return payment;
  }

  Optional<PaymentDto> findById(String id) {
    return Optional.ofNullable(payments.get(id));
  }
}
