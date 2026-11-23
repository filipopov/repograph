package dev.demobank.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.NotificationDto;
import dev.demobank.contracts.PaymentCompletedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NotificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

  private final NotificationService service = new NotificationService(Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void paymentCreatesNotificationForSenderAndReceiver() {
    service.onPaymentCompleted(new PaymentCompletedEvent("pay-1", "acc-alice", "acc-bob",
        new BigDecimal("100.00"), "CHF", NOW, Channel.MOBILE));

    assertThat(service.forAccount("acc-alice")).extracting(NotificationDto::message)
        .containsExactly("You sent 100.00 CHF to acc-bob via MOBILE");
    assertThat(service.forAccount("acc-bob")).extracting(NotificationDto::message)
        .containsExactly("You received 100.00 CHF from acc-alice via MOBILE");
    assertThat(service.forAccount("acc-carol")).isEmpty();
  }

  @Test
  void legacyEventWithoutChannelDefaultsToWeb() {
    service.onPaymentCompleted(new PaymentCompletedEvent("pay-1", "acc-alice", "acc-bob",
        new BigDecimal("100.00"), "CHF", NOW, null));

    assertThat(service.forAccount("acc-alice")).extracting(NotificationDto::message)
        .containsExactly("You sent 100.00 CHF to acc-bob via WEB");
    assertThat(service.forAccount("acc-bob")).extracting(NotificationDto::message)
        .containsExactly("You received 100.00 CHF from acc-alice via WEB");
  }
}
