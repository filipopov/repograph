package shop.billing;

import java.math.BigDecimal;
import org.springframework.kafka.annotation.KafkaListener;
import shop.contracts.OrderDto;
import shop.contracts.OrderPlacedEvent;
import shop.contracts.Topics;

class InvoiceService {

  private final OrdersClient ordersClient;

  InvoiceService(OrdersClient ordersClient) {
    this.ordersClient = ordersClient;
  }

  @KafkaListener(topics = Topics.ORDER_PLACED)
  void onOrderPlaced(OrderPlacedEvent event) {
    OrderDto order = ordersClient.get(event.orderId());
    BigDecimal total = order.amount().multiply(new BigDecimal("1.081"));
    System.out.println("Invoice " + total);
  }

  OrderDto reload(String id) {
    return ordersClient.get(com.acme.Ids.normalize(id));
  }
}
