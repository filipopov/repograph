package dev.demobank.payments;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
class PaymentController {

  private final PaymentService service;

  PaymentController(PaymentService service) {
    this.service = service;
  }

  /** Always 201: rejected and failed payments are recorded outcomes, see {@code status}. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  PaymentDto create(@RequestBody PaymentRequest request,
      @RequestHeader(value = "X-Channel", defaultValue = "WEB") Channel channel) {
    return service.execute(request, channel);
  }

  @GetMapping("/{id}")
  PaymentDto get(@PathVariable String id) {
    return service.get(id);
  }
}
