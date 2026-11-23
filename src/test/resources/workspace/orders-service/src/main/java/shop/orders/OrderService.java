package shop.orders;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import shop.contracts.OrderDto;
import shop.contracts.OrderPlacedEvent;
import shop.contracts.Topics;

@Service
class OrderService {

  private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;

  OrderService(KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  OrderDto place(OrderDto order) {
    kafkaTemplate.send(Topics.ORDER_PLACED, order.id(), new OrderPlacedEvent(order.id(), order.customerId()));
    return order;
  }
}
