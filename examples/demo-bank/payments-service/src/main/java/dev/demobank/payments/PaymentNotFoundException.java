package dev.demobank.payments;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
class PaymentNotFoundException extends RuntimeException {

  PaymentNotFoundException(String paymentId) {
    super("Payment not found: " + paymentId);
  }
}
