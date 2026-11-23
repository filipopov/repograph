package dev.demobank.notifications;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.NotificationDto;
import dev.demobank.contracts.PaymentCompletedEvent;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Turns payment events into customer notifications (stored in memory, no real delivery). */
@Service
class NotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

  private final List<NotificationDto> notifications = new CopyOnWriteArrayList<>();
  private final Clock clock;

  NotificationService(Clock clock) {
    this.clock = clock;
  }

  void onPaymentCompleted(PaymentCompletedEvent event) {
    // Legacy events published before the channel existed have no channel.
    Channel channel = Channel.orDefault(event.channel());
    add(event.sourceAccountId(), "You sent %s %s to %s via %s".formatted(
        event.amount(), event.currency(), event.targetAccountId(), channel));
    add(event.targetAccountId(), "You received %s %s from %s via %s".formatted(
        event.amount(), event.currency(), event.sourceAccountId(), channel));
    LOG.info("Created notifications for payment {}", event.paymentId());
  }

  List<NotificationDto> forAccount(String accountId) {
    return notifications.stream().filter(n -> n.accountId().equals(accountId)).toList();
  }

  private void add(String accountId, String message) {
    notifications.add(new NotificationDto("ntf-" + UUID.randomUUID(), accountId, message, clock.instant()));
  }
}
