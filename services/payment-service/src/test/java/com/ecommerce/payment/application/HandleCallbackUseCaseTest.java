package com.ecommerce.payment.application;

import com.ecom.common.event.PaymentCompletedV1;
import com.ecommerce.payment.domain.PaymentIntent;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.infrastructure.messaging.PaymentEventPublisher;
import com.ecommerce.payment.infrastructure.persistence.PaymentIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test idempotency của HandleCallbackUseCase với mock repo + publisher.
 * KHÔNG cần DB — DB layer được verify ở
 * {@code PaymentCallbackIdempotencyIT} (Testcontainers, gated).
 *
 * <p>3 case mục tiêu:
 * <ol>
 *   <li>Happy path: callback lần đầu SUCCESS → state=CAPTURED + publish event.</li>
 *   <li>Duplicate (fast path): callback lần 2 cùng providerTxnId → no-op, no event.</li>
 *   <li>Race (UNIQUE constraint): 2 concurrent INSERT cùng txn → 1 thắng, 1 catch
 *       DataIntegrityViolationException → idempotent response.</li>
 * </ol>
 */
class HandleCallbackUseCaseTest {

    private PaymentIntentRepository repository;
    private PaymentEventPublisher publisher;
    private HandleCallbackUseCase useCase;

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final String PROVIDER = "MOCK";
    private static final String TXN = "TXN-001";

    @BeforeEach
    void setUp() {
        repository = mock(PaymentIntentRepository.class);
        publisher = mock(PaymentEventPublisher.class);
        useCase = new HandleCallbackUseCase(repository, publisher);
    }

    @Test
    @DisplayName("Happy path: SUCCESS lần đầu → CAPTURED + publish payment.completed")
    void successFirstCall_publishes() {
        PaymentIntent intent = newInitiated();
        when(repository.findByProviderAndProviderTxnId(PROVIDER, TXN)).thenReturn(Optional.empty());
        when(repository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(repository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = useCase.execute(new CallbackCommand(
                intent.getId(), PROVIDER, TXN, CallbackCommand.Outcome.SUCCESS, null));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.eventPublished()).isTrue();
        assertThat(result.intent().getStatus()).isInstanceOf(PaymentStatus.Captured.class);
        verify(publisher, times(1)).publishPaymentCompleted(any(PaymentCompletedV1.class));
    }

    @Test
    @DisplayName("Duplicate fast-path: lookup by (provider, txnId) hit → no event, duplicate=true")
    void duplicateFastPath_noPublish() {
        PaymentIntent existing = newInitiated();
        existing.capture(TXN); // đã CAPTURED từ lần callback trước
        when(repository.findByProviderAndProviderTxnId(PROVIDER, TXN)).thenReturn(Optional.of(existing));

        var result = useCase.execute(new CallbackCommand(
                existing.getId(), PROVIDER, TXN, CallbackCommand.Outcome.SUCCESS, null));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.eventPublished()).isFalse();
        assertThat(result.intent().getStatus()).isInstanceOf(PaymentStatus.Captured.class);
        verify(publisher, never()).publishPaymentCompleted(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Race: UNIQUE constraint violation → catch + lookup duplicate, no event")
    void uniqueConstraintRace_returnsDuplicate() {
        PaymentIntent intent = newInitiated();
        PaymentIntent winnerCapture = newInitiated();
        winnerCapture.capture(TXN);

        when(repository.findByProviderAndProviderTxnId(PROVIDER, TXN))
                .thenReturn(Optional.empty())              // fast-path miss
                .thenReturn(Optional.of(winnerCapture));   // sau khi catch UNIQUE
        when(repository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(repository.saveAndFlush(any(PaymentIntent.class)))
                .thenThrow(new DataIntegrityViolationException("uq_payment_provider_txn"));

        var result = useCase.execute(new CallbackCommand(
                intent.getId(), PROVIDER, TXN, CallbackCommand.Outcome.SUCCESS, null));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.eventPublished()).isFalse();
        verify(publisher, never()).publishPaymentCompleted(any());
        verify(repository, times(2))
                .findByProviderAndProviderTxnId(eq(PROVIDER), anyString());
    }

    @Test
    @DisplayName("FAILED outcome: state=FAILED + KHÔNG publish payment.completed")
    void failedOutcome_noPublish() {
        PaymentIntent intent = newInitiated();
        when(repository.findByProviderAndProviderTxnId(PROVIDER, TXN)).thenReturn(Optional.empty());
        when(repository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(repository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = useCase.execute(new CallbackCommand(
                intent.getId(), PROVIDER, TXN, CallbackCommand.Outcome.FAILED, "Card declined"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.eventPublished()).isFalse();
        assertThat(result.intent().getStatus()).isInstanceOf(PaymentStatus.Failed.class);
        assertThat(result.intent().getFailureReason()).isEqualTo("Card declined");
        verify(publisher, never()).publishPaymentCompleted(any());
    }

    @Test
    @DisplayName("Unknown paymentId → PAYMENT_NOT_FOUND business exception")
    void unknownPaymentId_throws() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByProviderAndProviderTxnId(PROVIDER, TXN)).thenReturn(Optional.empty());
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                com.ecom.common.exception.BusinessException.class,
                () -> useCase.execute(new CallbackCommand(
                        unknown, PROVIDER, TXN, CallbackCommand.Outcome.SUCCESS, null)));
    }

    @Test
    @DisplayName("Provider mismatch: callback provider khác intent.provider → 400")
    void providerMismatch_throws() {
        PaymentIntent intent = newInitiated(); // provider=MOCK
        when(repository.findByProviderAndProviderTxnId("VNPAY", TXN)).thenReturn(Optional.empty());
        when(repository.findById(intent.getId())).thenReturn(Optional.of(intent));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.ecom.common.exception.BusinessException.class,
                () -> useCase.execute(new CallbackCommand(
                        intent.getId(), "VNPAY", TXN, CallbackCommand.Outcome.SUCCESS, null)));
    }

    private static PaymentIntent newInitiated() {
        return PaymentIntent.initiate(ORDER_ID, new BigDecimal("100000"), "VND", PROVIDER);
    }
}
