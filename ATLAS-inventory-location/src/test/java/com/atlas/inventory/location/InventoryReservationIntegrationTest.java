    void restoreReturnsOnlyClaimedStockAndIsIdempotent() {
        seedStock("restore-sku", 2);
        InventoryReservationResponse claimed = inventory.reserve(FULFILLMENT_LOCATION_ID, "restore-" + UUID.randomUUID(),
                request(UUID.randomUUID(), new Item(SELLER_ID, "restore-sku", 1)));
        InventoryReservationResponse reservedOnly = inventory.reserve(FULFILLMENT_LOCATION_ID,
                "restore-reserved-" + UUID.randomUUID(), request(UUID.randomUUID(), new Item(SELLER_ID, "restore-sku", 1)));
        inventory.claim(FULFILLMENT_LOCATION_ID, claimed.reservationId());

        InventoryReservationResponse restored = inventory.restore(FULFILLMENT_LOCATION_ID, claimed.reservationId());
        InventoryReservationResponse replay = inventory.restore(FULFILLMENT_LOCATION_ID, claimed.reservationId());

        assertThat(restored.status()).isEqualTo("RELEASED");
        assertThat(replay.replayed()).isTrue();
        assertStock("restore-sku", 2, 1, 1);
        // 판매 확정 전 예약은 restore가 아니라 cancel/release 대상이다.
        assertThatThrownBy(() -> inventory.restore(FULFILLMENT_LOCATION_ID, reservedOnly.reservationId()))
                .isInstanceOf(InventoryLocationException.class);
        assertStock("restore-sku", 2, 1, 1);
    }

    @Test
    void restockReturnsSoldUnitsOncePerKeyAndNeverExceedsTheSoldQuantity() {
        seedStock("restock-sku", 3);
        InventoryReservationResponse sold = inventory.reserve(FULFILLMENT_LOCATION_ID, "restock-" + UUID.randomUUID(),
                request(UUID.randomUUID(), new Item(SELLER_ID, "restock-sku", 2)));
        inventory.claim(FULFILLMENT_LOCATION_ID, sold.reservationId());
        String firstRefund = "refund-restock:" + UUID.randomUUID();

        InventoryReservationResponse first = inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                firstRefund, List.of(new Item(SELLER_ID, "restock-sku", 1)));
        InventoryReservationResponse replay = inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                firstRefund, List.of(new Item(SELLER_ID, "restock-sku", 1)));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        var stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "restock-sku");
        assertThat(stock.available()).isEqualTo(2);
        assertThat(stock.claimed()).isEqualTo(1);
        assertThatThrownBy(() -> inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(), firstRefund,
                List.of(new Item(SELLER_ID, "restock-sku", 2))))
                .isInstanceOfSatisfying(InventoryLocationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(InventoryLocationException.Code.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(),
                "refund-restock:" + UUID.randomUUID(), List.of(new Item(SELLER_ID, "restock-sku", 2))))
                .isInstanceOf(InventoryLocationException.class);
        // 일부 재입고된 판매분을 통째로 되돌리면 같은 수량이 두 번 판매 가능해지므로 거절한다.
        assertThatThrownBy(() -> inventory.restore(FULFILLMENT_LOCATION_ID, sold.reservationId()))
                .isInstanceOf(InventoryLocationException.class);
        inventory.restock(FULFILLMENT_LOCATION_ID, sold.reservationId(), "refund-restock:" + UUID.randomUUID(),
                List.of(new Item(SELLER_ID, "restock-sku", 1)));
        stock = inventory.findStock(FULFILLMENT_LOCATION_ID, SELLER_ID, "restock-sku");
        assertThat(stock.available()).isEqualTo(3);
        assertThat(stock.claimed()).isZero();
        assertThat(stock.available() + stock.reserved() + stock.claimed()).isEqualTo(stock.initialCapacity());
    }

    @Test
    void availabilityReturnsZeroForAnUnseededSkuAndNotFoundForAnUnknownLocation() throws Exception {
        mockMvc.perform(get("/internal/v1/fulfillment-locations/{locationId}/availability/{sellerId}/{skuId}",
                        FULFILLMENT_LOCATION_ID, SELLER_ID, "unseeded-sku")
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0));
        mockMvc.perform(get("/internal/v1/fulfillment-locations/{locationId}/availability/{sellerId}/{skuId}",
                        UUID.randomUUID(), SELLER_ID, "unseeded-sku")
                        .header("X-Atlas-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isNotFound());
    }
