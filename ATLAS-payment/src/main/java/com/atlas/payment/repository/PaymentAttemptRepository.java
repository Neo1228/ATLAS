package com.atlas.payment.repository;

import com.atlas.payment.persistence.entity.PaymentAttempt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO payment_attempt
                (order_id, attempt_id, state, payment_phase, authorization_active, total_won)
            VALUES (:orderId, :attemptId, 'NOT_STARTED', 'AUTHORIZATION', FALSE, :totalWon)
            ON CONFLICT (order_id) DO UPDATE
            SET total_won = EXCLUDED.total_won
            WHERE payment_attempt.state = 'NOT_STARTED' AND payment_attempt.total_won IS NULL
            """, nativeQuery = true)
    void insertInitial(@Param("orderId") UUID orderId, @Param("totalWon") long totalWon,
            @Param("attemptId") UUID attemptId);

    @Modifying
    @Query(value = """
            INSERT INTO payment_attempt
                (checkout_id, attempt_id, state, payment_phase, authorization_active, total_won)
            VALUES (:checkoutId, :attemptId, 'NOT_STARTED', 'AUTHORIZATION', FALSE, :totalWon)
            ON CONFLICT (checkout_id) DO UPDATE
            SET total_won = EXCLUDED.total_won
            WHERE payment_attempt.state = 'NOT_STARTED' AND payment_attempt.total_won IS NULL
            """, nativeQuery = true)
    void insertCheckoutInitial(@Param("checkoutId") UUID checkoutId, @Param("totalWon") long totalWon,
            @Param("attemptId") UUID attemptId);

    Optional<PaymentAttempt> findByOrderId(UUID orderId);
    Optional<PaymentAttempt> findByCheckoutId(UUID checkoutId);
    Optional<PaymentAttempt> findByProviderCaptureId(String providerCaptureId);
}
