package dev.demobank.notifications;

import dev.demobank.contracts.PaymentCompletedEvent;
import dev.demobank.contracts.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class PaymentEventListener {

  private final NotificationService notificationService;

  PaymentEventListener(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @KafkaListener(topics = Topics.PAYMENTS_COMPLETED)
  void onPaymentCompleted(PaymentCompletedEvent event) {
    notificationService.onPaymentCompleted(event);
  }
}
