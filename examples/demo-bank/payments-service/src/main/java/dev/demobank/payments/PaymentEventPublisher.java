package dev.demobank.payments;

import dev.demobank.contracts.PaymentCompletedEvent;
import dev.demobank.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class PaymentEventPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentEventPublisher.class);

  private final KafkaTemplate<String, PaymentCompletedEvent> kafkaTemplate;

  PaymentEventPublisher(KafkaTemplate<String, PaymentCompletedEvent> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  void publishCompleted(PaymentCompletedEvent event) {
    kafkaTemplate.send(Topics.PAYMENTS_COMPLETED, event.paymentId(), event)
        .whenComplete((result, error) -> {
          if (error != null) {
            LOG.error("Failed to publish PaymentCompletedEvent for {}", event.paymentId(), error);
          }
        });
  }
}
