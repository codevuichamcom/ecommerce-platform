package com.ecommerce.payment.infrastructure.persistence;

import com.ecommerce.payment.domain.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository cho {@link PaymentIntent}.
 *
 * <p>{@link #findByProviderAndProviderTxnId} là idempotent lookup key —
 * gọi sau khi catch DataIntegrityViolationException từ UNIQUE constraint
 * để lấy existing row trả về client (xem
 * {@link com.ecommerce.payment.application.HandleCallbackUseCase}).
 */
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByProviderAndProviderTxnId(String provider, String providerTxnId);
}
