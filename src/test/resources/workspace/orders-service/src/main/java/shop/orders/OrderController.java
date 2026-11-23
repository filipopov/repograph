package shop.orders;

import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;
import shop.contracts.OrderDto;

@RestController
@RequestMapping("/orders")
class OrderController {

  private final OrderService service;

  OrderController(OrderService service) {
    this.service = service;
  }

  @GetMapping("/{orderId}")
  OrderDto get(@PathVariable String orderId) {
    return new OrderDto(orderId, BigDecimal.TEN, "c-1");
  }

  @PostMapping
  OrderDto place(@RequestBody OrderDto order) {
    return service.place(order);
  }
}
