package dev.demobank.payments;

import dev.demobank.contracts.Channel;
import dev.demobank.contracts.FraudCheckRequest;
import dev.demobank.contracts.FraudCheckResult;
import dev.demobank.contracts.MoneyMovementRequest;
import dev.demobank.contracts.PaymentCompletedEvent;
import dev.demobank.contracts.PaymentDto;
import dev.demobank.contracts.PaymentRequest;
import dev.demobank.contracts.PaymentStatus;
import dev.demobank.payments.client.AccountsClient;
import dev.demobank.payments.client.FraudClient;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * Orchestrates a payment: fraud check, debit source, credit target, publish event.
 * A failed credit is compensated by crediting the source back.
 */
@Service
class PaymentService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);

  private final FraudClient fraudClient;
  private final AccountsClient accountsClient;
  private final PaymentRepository repository;
  private final PaymentEventPublisher eventPublisher;
  private final Clock clock;

  PaymentService(FraudClient fraudClient, AccountsClient accountsClient,
      PaymentRepository repository, PaymentEventPublisher eventPublisher, Clock clock) {
    this.fraudClient = fraudClient;
    this.accountsClient = accountsClient;
    this.repository = repository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  PaymentDto execute(PaymentRequest request, Channel channel) {
    String paymentId = "pay-" + UUID.randomUUID();
    Instant now = clock.instant();

    FraudCheckResult fraud = fraudClient.check(new FraudCheckRequest(
        request.sourceAccountId(), request.targetAccountId(), request.amount(), request.currency()));
    if (!fraud.approved()) {
      LOG.warn("Payment {} rejected by fraud check: {}", paymentId, fraud.reason());
      return repository.save(payment(paymentId, request, PaymentStatus.REJECTED, fraud.reason(), now,
          channel));
    }

    MoneyMovementRequest movement =
        new MoneyMovementRequest(request.amount(), request.currency(), paymentId);
    try {
      accountsClient.debit(request.sourceAccountId(), movement);
    } catch (RestClientResponseException e) {
      LOG.warn("Payment {} failed: debit of {} returned {}", paymentId, request.sourceAccountId(),
          e.getStatusCode());
      return repository.save(payment(paymentId, request, PaymentStatus.FAILED,
          "Debit failed: " + e.getStatusCode(), now, channel));
    }

    try {
      accountsClient.credit(request.targetAccountId(), movement);
    } catch (RestClientResponseException e) {
      LOG.error("Payment {}: credit of {} returned {}, reversing debit", paymentId,
          request.targetAccountId(), e.getStatusCode());
      accountsClient.credit(request.sourceAccountId(), movement);
      return repository.save(payment(paymentId, request, PaymentStatus.FAILED,
          "Credit failed: " + e.getStatusCode() + ", debit reversed", now, channel));
    }

    PaymentDto completed = repository.save(payment(paymentId, request, PaymentStatus.COMPLETED, null, now,
        channel));
    eventPublisher.publishCompleted(new PaymentCompletedEvent(paymentId, request.sourceAccountId(),
        request.targetAccountId(), request.amount(), request.currency(), now, channel));
    LOG.info("Payment {} completed: {} {} {} -> {} via {}", paymentId, request.amount(),
        request.currency(), request.sourceAccountId(), request.targetAccountId(), channel);
    return completed;
  }

  PaymentDto get(String id) {
    return repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
  }

  private static PaymentDto payment(String id, PaymentRequest request, PaymentStatus status,
      String failureReason, Instant createdAt, Channel channel) {
    return new PaymentDto(id, request.sourceAccountId(), request.targetAccountId(), request.amount(),
        request.currency(), request.reference(), status, failureReason, createdAt, channel);
  }
}
