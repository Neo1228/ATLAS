package com.atlas.payment.repository;

import com.atlas.payment.persistence.entity.PaymentAttempt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

    @Modifying
    @Query(value = "INSERT INTO payment_attempt (order_id, attempt_id, state, payment_phase, authorization_active) "
            + "VALUES (:orderId, :attemptId, 'NOT_STARTED', 'AUTHORIZATION', FALSE) "
            + "ON CONFLICT (order_id) DO NOTHING", nativeQuery = true)
    int insertInitial(@Param("orderId") UUID orderId, @Param("attemptId") UUID attemptId);
}
