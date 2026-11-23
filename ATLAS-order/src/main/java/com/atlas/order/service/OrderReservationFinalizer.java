import java.time.Duration;
    public double oldestPendingAgeSeconds(BuyerCheckoutState state) {
        if (state != BuyerCheckoutState.RESERVATION_PENDING && state != BuyerCheckoutState.CANCEL_PENDING) {
            throw new IllegalArgumentException("Unsupported checkout recovery state");
        }
        Instant oldestPendingAt = state == BuyerCheckoutState.RESERVATION_PENDING
                ? checkouts.findOldestRemoteCreatedAtByState(state)
                : checkouts.findOldestRemoteCancellationRequestedAt();
        if (oldestPendingAt == null) return 0;
        return Math.max(0, Duration.between(oldestPendingAt, clock.instant()).toMillis() / 1000.0);
    }

        Instant observedAt = clock.instant();
        boolean deadlinePassed = childOrders.stream()
                .anyMatch(order -> !observedAt.isBefore(order.checkoutExpiresAt()))
                || outcomes.values().stream().flatMap(response -> response.locations().stream())
                .anyMatch(location -> location.expiresAt() != null && !observedAt.isBefore(location.expiresAt()));
        if (deadlinePassed) {
            compensate(checkoutId, childOrders);
            return;
        }
                        && !response.locations().isEmpty()
        Instant remoteDeadline = outcomes.values().stream()
                .flatMap(response -> response.locations().stream())
                .map(location -> location.expiresAt())
                .min(Instant::compareTo).orElseThrow();
            if (!now.isBefore(remoteDeadline)
                    || current.stream().anyMatch(order -> !now.isBefore(order.checkoutExpiresAt()))) return null;
            Instant checkoutDeadline = current.stream().map(PurchaseOrder::checkoutExpiresAt)
                    .min(Instant::compareTo).filter(deadline -> deadline.isBefore(remoteDeadline))
                    .orElse(remoteDeadline);
            current.forEach(order -> order.limitCheckoutExpiry(checkoutDeadline));