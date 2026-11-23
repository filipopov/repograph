package dev.demobank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.FraudCheckResult;
import dev.demobank.contracts.PaymentCompletedEvent;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentRequest;
import dev.demobank.contracts.PaymentStatus;
import dev.demobank.payments.client.AccountsClient;
import dev.demobank.payments.client.FraudClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
  private static final PaymentRequest REQUEST =
      new PaymentRequest("acc-alice", "acc-bob", new BigDecimal("100.00"), "CHF", "rent");

  @Mock
  private FraudClient fraudClient;
  @Mock
  private AccountsClient accountsClient;
  @Mock
  private PaymentEventPublisher eventPublisher;

  private PaymentService service;

  @BeforeEach
  void setUp() {
    service = new PaymentService(fraudClient, accountsClient, new PaymentRepository(), eventPublisher,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void completesPaymentAndPublishesEvent() {
    when(fraudClient.check(any())).thenReturn(new FraudCheckResult(true, 10, "Low risk"));

    PaymentDto result = service.execute(REQUEST, Channel.MOBILE);

    assertThat(result.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(result.channel()).isEqualTo(Channel.MOBILE);
    verify(accountsClient).debit(eq("acc-alice"), any());
    verify(accountsClient).credit(eq("acc-bob"), any());
    verify(eventPublisher).publishCompleted(new PaymentCompletedEvent(result.id(), "acc-alice",
        "acc-bob", new BigDecimal("100.00"), "CHF", NOW, Channel.MOBILE));
    assertThat(service.get(result.id())).isEqualTo(result);
    assertThat(service.get(result.id()).channel()).isEqualTo(Channel.MOBILE);
  }

  @Test
  void rejectedByFraudDoesNotMoveMoney() {
    when(fraudClient.check(any())).thenReturn(new FraudCheckResult(false, 95, "Too much"));

    PaymentDto result = service.execute(REQUEST, Channel.MOBILE);

    assertThat(result.status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(result.failureReason()).isEqualTo("Too much");
    assertThat(service.get(result.id()).channel()).isEqualTo(Channel.MOBILE);
    verify(accountsClient, never()).debit(any(), any());
    verify(eventPublisher, never()).publishCompleted(any());
  }

  @Test
  void failedDebitMarksPaymentFailed() {
    when(fraudClient.check(any())).thenReturn(new FraudCheckResult(true, 10, "Low risk"));
    when(accountsClient.debit(eq("acc-alice"), any())).thenThrow(httpError(HttpStatus.CONFLICT));

    PaymentDto result = service.execute(REQUEST, Channel.WEB);

    assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    verify(accountsClient, never()).credit(any(), any());
    verify(eventPublisher, never()).publishCompleted(any());
  }

  @Test
  void failedCreditReversesDebit() {
    when(fraudClient.check(any())).thenReturn(new FraudCheckResult(true, 10, "Low risk"));
    when(accountsClient.credit(eq("acc-bob"), any())).thenThrow(httpError(HttpStatus.BAD_REQUEST));

    PaymentDto result = service.execute(REQUEST, Channel.WEB);

    assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(result.failureReason()).contains("debit reversed");
    verify(accountsClient).credit(eq("acc-alice"), any());
    verify(eventPublisher, never()).publishCompleted(any());
  }

  private static HttpClientErrorException httpError(HttpStatus status) {
    return HttpClientErrorException.create(status, status.getReasonPhrase(), null, new byte[0],
        StandardCharsets.UTF_8);
  }
}
