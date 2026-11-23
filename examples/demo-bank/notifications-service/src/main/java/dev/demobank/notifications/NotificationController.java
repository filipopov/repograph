package dev.demobank.notifications;

import dev.demobank.contracts.NotificationDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
class NotificationController {

  private final NotificationService service;

  NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping
  List<NotificationDto> forAccount(@RequestParam String accountId) {
    return service.forAccount(accountId);
  }
}
