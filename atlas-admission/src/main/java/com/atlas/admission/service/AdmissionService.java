package com.atlas.admission.service;

import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.model.CheckoutSlotReleaseReason;

import java.time.Duration;
import java.util.UUID;

public interface AdmissionService {
    int expireDueTickets();

    /**
     * 종료된(EXPIRED·CHECKOUT_FAILED) ticket 중 retention보다 오래됐고 어떤 주문도 가리키지 않는 것을 최대 batchSize개 지운다.
     * 주문이 가리키는 ticket은 주문 조회가 계속 읽으므로 남긴다(ADR-0088).
     */
    int purgeTombstones(Duration retention, int batchSize);

    void configureSellerSkuPolicy(AdmissionRequest.ConfigureSellerSkuPolicy request);
    AdmissionResponse.QueueTicket join(AdmissionRequest.JoinQueue request);

    /** 마지막으로 저장된 티켓 상태를 반환한다. 만료와 승격은 스케줄러 또는 상태 변경 작업에서 처리한다. */
    AdmissionResponse.QueueTicket find(UUID campaignId, UUID ticketId);

    AdmissionResponse.QueueTicket requireCheckoutSlot(UUID campaignId, UUID ticketId);
    AdmissionResponse.QueueTicket startCheckout(UUID campaignId, UUID ticketId);
    AdmissionResponse.QueueTicket releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

}
