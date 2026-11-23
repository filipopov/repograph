package dev.demobank.gateway.client;

import dev.demobank.contracts.NotificationDto;
import java.util.List;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** HTTP client for notifications-service. */
@HttpExchange("/notifications")
public interface NotificationsClient {

  @GetExchange
  List<NotificationDto> forAccount(@RequestParam String accountId);
}
