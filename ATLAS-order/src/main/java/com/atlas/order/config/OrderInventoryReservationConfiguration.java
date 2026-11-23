package com.atlas.order.config;

import com.atlas.order.repository.OrderInventoryReservationRepository;
import com.atlas.order.service.OrderInventoryReservationCoordinator;
import com.atlas.order.service.InventoryLocationReservationClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class OrderInventoryReservationConfiguration {
    @Bean
    public OrderInventoryReservationCoordinator orderInventoryReservationCoordinator(
            OrderInventoryReservationRepository repository,
            InventoryLocationReservationClient inventory,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${atlas.order-inventory-reservation.recovery.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${atlas.order-inventory-reservation.recovery.base-backoff:PT1S}") Duration baseBackoff,
            @Value("${atlas.order-inventory-reservation.recovery.max-backoff:PT1M}") Duration maxBackoff,
            @Value("${atlas.order-inventory-reservation.recovery.minimum-reservation-remaining:PT10S}")
            Duration minimumReservationRemaining,
            @Value("${atlas.order-inventory-reservation.recovery.max-attempts:8}") int maxAttempts,
            @Value("${atlas.order-inventory-reservation.connect-timeout:250ms}") Duration connectTimeout,
            @Value("${atlas.order-inventory-reservation.read-timeout:PT2S}") Duration readTimeout) {
        validateLeaseTiming(leaseDuration, connectTimeout, readTimeout);
        return new OrderInventoryReservationCoordinator(repository, inventory, clock, transactionManager,
                leaseDuration, baseBackoff, maxBackoff, minimumReservationRemaining, maxAttempts);
    }

    static void validateLeaseTiming(Duration leaseDuration, Duration connectTimeout, Duration readTimeout) {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Inventory lease and HTTP timeouts must be positive");
        }
        Duration renewalWindow = connectTimeout.plus(readTimeout).plusSeconds(1);
        if (leaseDuration.dividedBy(2).compareTo(renewalWindow) <= 0) {
            throw new IllegalArgumentException("Half the reservation lease must exceed HTTP timeouts plus 1 second");
        }
    }
}
