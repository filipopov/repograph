package shop.billing;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import shop.contracts.OrderDto;

@HttpExchange("/orders")
public interface OrdersClient {

  @GetExchange("/{id}")
  OrderDto get(@PathVariable String id);

  @GetExchange("/archive/{id}")
  OrderDto archived(@PathVariable String id);
}
