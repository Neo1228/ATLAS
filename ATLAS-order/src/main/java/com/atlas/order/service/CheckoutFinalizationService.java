package com.atlas.order.service;

import com.atlas.order.persistence.state.BuyerCheckoutState;
import java.util.UUID;

public interface CheckoutFinalizationService {
    double oldestPendingAgeSeconds(BuyerCheckoutState state);

    /**
     * checkout 하나의 원격 예약 완료를 시도한다. 실패하면 checkout을
     * {@code RESERVATION_PENDING}으로 남겨 재시작 후 같은 요청 내용으로 재시도할 수 있게 한다.
     */
    void finalizeCheckout(UUID checkoutId);

    /** 요청 thread에서 예약 확정을 끝내지 못한 checkout의 복구 진입점. */
    int finalizeDue(int batchSize);
}
